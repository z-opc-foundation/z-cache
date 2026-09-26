# Changelog

All notable changes to z-cache will be documented in this file.

## [1.3.6] - Unreleased

### Fixed

#### 同一个 JVM 里两台服务器互相改写对方的状态（作用域，不是内存序）
- 1.3.5 只把 pub/sub 管理器与连接登记表收成"一台一份"，`StreamStore` / `SlowLog`
  / `AofPersistence` / `RdbPersistence` / `loading` 这五个仍然挂在 `CommandHandler` 的静态字段上，
  由"最后启动的那台"说了算。两条都在 socket 这一侧量到了：
  - A 上 `XADD bleed:stream`，B 启动之后 B 上 `XLEN bleed:stream` 回 `:1` ——
    两台服务器的 Stream 键空间是同一份。
  - 更贵的一条：B 不带 `--data-dir`，`initPersistence()` 里那两行
    `CommandHandler.setAofPersistence(null)` / `setRdbPersistence(null)` 把**A 的**写盘入口擦了。
    A 照常接受 `SET`，但 `SAVE` 变成
    `-ERR SAVE is not supported: no data directory configured`，停机快照也没有 ——
    进程里只要先后起过两台，先起那台的数据就静默不落盘。
- 现在这一份台份归一个新对象 `ServerScope`（pub/sub 管理器、连接登记表、MONITOR 集合、
  `StreamStore`、`SlowLog`，以及启动过程中才挂上来的 RDB / AOF / `loading`），
  `RedisServer` 构造时建自己那一份，`RedisServerHandler` 在搭管道时把它交给这条连接的
  `CommandHandler`。静态字段退成"没绑过服务器时"的进程级默认值——嵌入式手工 handler 与
  直接 `new CommandHandler(store)` 的单测走那条，行为不变。
- `RedisServerHandler` 的四个构造器收成一个（`(CommandHandler, ServerScope, MemoryStore,
  EventExecutorGroup)`）：两参/三参/六参那三个在仓库里零调用方，留着只会让人以为
  "可以只传一个 pubSubManager"。

#### `RENAME` 把源键"并进"目标键，而不是顶掉它
- 五条类型分支里只有 String 那条走 `setDb`（经 `MemoryStore.putDb` 的 `clearOtherTypes` 抹掉旧值）。
  四条集合分支是 `hgetall(src) → del(src) → hmset(dst, …)`：dst 原有内容原样留着。
  socket 侧实测 `HSET d old 1; HSET s f v; RENAME s d` 之后 `HLEN d` 回 `:2`（Redis 是 `:1`），
  `HEXISTS d old` 回 `:1`。
- 跨类型更糟：dst 是 hash、src 是 list 时，list 写进 `listStores`、旧 hash 还留在 `hashStores`，
  同一个键名下并存两种类型 —— 正是 1.3.5 类型闸门要消灭的状态，而 `RENAME` 是它现成的生产者
  （`RENAME` 有意不在闸门表里，Redis 允许跨类型改名）。
- 源键"在不在"的判据也从 `store.existsDb` 换成 `typeOfDb`：前者只认 String 表里记着的键，
  对集合键回 false，等于五条支路五把尺。
- `DEL` 与 `RENAME` 现在共用 `deleteEveryType`：一个键名底下五张表全清，`DBSIZE` 不会再
  把一个跨类型残留多算一个键。

#### 浮点文本的读与写：一把 Java 文法通吃两族，边界和写法都错位
- 参考实现有**两族**浮点，不是一把 double。分数那一族（`ZSCORE` / `ZINCRBY` / `ZADD` 的分数位 /
  `*RANGE … WITHSCORES`）是 `strtod` 进、`%.17g` 出；人读那一族（`INCRBYFLOAT` / `HINCRBYFLOAT`）
  是 `strtold` 进（x87 的 80 位 long double）、`%.17Lf` 去尾零出。1.3.5 之前两处都写
  `Double.parseDouble` + `Double.toString`，两族就都不是参考实例那串：分数族要 17 位有效数字，
  `Double.toString(0.1)` 交 `0.1` 而实例打 `0.10000000000000001`；人读族永不用科学计数，
  `Double.toString(1e-7)` 交 `1.0E-7` 而实例打 `0.0000001`，`1e17` 实例打 `100000000000000000`；
  而 `0.33333333333333333333` 只要经由 double 就只剩 16 个 3，实例回 17 个。
- 现在这一族由 `RedisDoubleFormat` 单独承载，两族各有各的入口：`format` / `parse` 是 double 那一族，
  `plainSum` / `plainSumAllowingNonFinite` / `requirePlain` 是 long double 那一族（用 `BigDecimal`
  精确落格，不走 double，否则 `0x1p+5000` 这种参考实例交得出 1506 位的写法会先被压成无穷）。
- **下溢在这一侧是错误，不是 0**：`strtod` / `strtold` 把非零文本舍到 0 时置 `ERANGE`，
  Redis 的 `string2d` / `string2ld` 见 `errno != 0` 就回 `value is not a valid float`。
  于是界限落在"量级"上而不是某个十进制指数上：long double 一族 `1e-4951` 拒、`1.9e-4951` 收
  （收的那一侧只是印成 `0`）；double 一族 `1e-324` 拒、`1e-320` 与 `4.9e-324`（最小次正规）收。
  以前两条都朝反方向偏：long double 把下溢当 0 收下，double 把 `1e-400` 安静地解析成 0。
- **溢出同理**：`1e4000`、`0x1p+16384` 这些写法 Java 交出无穷而不报错，参考实现当场拒；
  long double 的上界是 `(2-2^-63)×2^16383`（十进制 4932 位那一档），不是 double 的 `1.8e308`。
- **C99 十六进制浮点**是参考实例认、Java 那两条文法都不认的一类：`0x10` 就是 16（指数可省，
  `Double.parseDouble` 强制要 `p`）、`0x.8p0` 就是 0.5、`0X1.8P+1` 大小写混着也算，
  而可表示范围是**目标格式**的范围——`0x1p+5000` 在 long double 一族收、`0x1p+1024` 在 double 一族拒。
- **无穷的词汇表两边都不照抄**：`strtod` 认 `inf` 与 `infinity` 两个词干（大小写随意、可带一个符号），
  实测 `Infinity` / `+Infinity` / `INFINITY` / `-Infinity` 全收并印回 `inf`；Java 的 `parseDouble`
  恰好相反——`Infinity` 收、`inf` 不收。`nan` 家族则死在解析阶段（`NAN` / `-nan` / `nan(1)` 都拒），
  而 `inf` 一路活到结果检查（`INCRBYFLOAT k inf` 回 `increment would produce NaN or Infinity`，
  `HINCRBYFLOAT h f Infinity` 根本没有这道闸，`inf` 就写进字段、下次再加还是 `inf`）。
- **Java 比 C 宽出的那几类写法一律拒**：类型后缀（`1d` `1D` `1F` `1.5d` `0x1p3f`）、
  Java 7 起的下划线分隔（`1_0`）、以及前后空白（空格与制表符同命，四个位置——分数位、增量位、
  原值位、`HINCRBYFLOAT` 的原值位——全拒）。`Double.parseDouble` 对本机 JDK 25 的 `" 1"` 与
  `"1\t"` 都交 1.0，所以拒它的不是 Java，是 `RedisDoubleFormat` 里那台自己写的 C99 文法机。
- 自己写出去的串必须自己读得回来：`COPY`、持久化回读、`HINCRBYFLOAT` 读旧值都是拿本类打出的那串
  再解析的，以前 `inf` 是"自己写、自己拒"。`whatWeWriteWeCanReadBack` 把这一条钉成 15 例往返。

#### RESP 帧被 TCP 切一刀，服务端多吐元素、客户端静默交错值
- 服务端与客户端的解码器曾经是两份逐字雷同的 `ReplayingDecoder` 子类，都在元素循环里手工
  `readerIndex(rewind)`：重放游标与手工 rewind 互不认识，同一族缺陷在两边各长了一次，症状还不同。
  250 实测服务端侧 `ZADD z1 1 one 2 two 3 three` 被切成两包后，交出正确数组**之外**再吐出 7 个
  散装元素，请求/响应就此错位；客户端侧 `*2 foo bar` 切在第 13 字节，解出 `["foo","foo"]`——
  一声不响把错值交给调用方，日志里什么都没有。
- 现在两边共用 `z-cache-common` 里的 `RespFrameReader`，规矩只有一条：**整帧到齐才前进游标**，
  字节不够就返回 `NEED_MORE` 且一个字都不消耗。`RespDecoder` 与 `ClientRespDecoder` 各减掉
  411 / 505 行重复状态机（两个文件合计 -893 行）。
- 错误回复这一族补了两处对不上参考实例的地方：`RespError` 构造时把消息里的 CR/LF 换成空格
  （Error 类型靠 CRLF 结束，文本里带一个换行就把这一行劈成几行，后面那段成了客户端从没请求过的
  响应——而报错文本经常原样回显客户端输入）；`wrongNumberOfArguments` 打的是命令表里的小写名
  （实测 `ZADD k CH` → `-ERR wrong number of arguments for 'zadd' command`，而调用点传进来的
  全是 `"ZADD"` 这种大写；`CLIENT` / `DEBUG` 那类"未知子命令"文案要保留原文大写，所以清洗
  只在各自出口做一次，不铺到几十个调用点）。

#### 位族命令整族缺席：五支按实测判序补齐，而 BITOP 答复的单位是字节
- `_doc/001_arch/01-module-structure.md:62,210-212` 把 `SETBIT / GETBIT / BITCOUNT / BITPOS /
  BITOP / BITFIELD` 六支写进展品清单，1.3.5 的 `CommandHandler` 里
  `handleBitcount` / `handleGetbit` / `handleSetbit` / `handleBitpos` / `handleBitop`
  **一个都不存在**（拿 `git show a1a744b:` 取那一棵树来 grep，命中 0），这五支当时一律回
  `unknown command`。BITFIELD 到这一版仍然没做，见下面"已知边界"。
- 位族不是一条共用管线，**谁先开口是每支命令各自的实测**（250 上 redis-server 4.0.9 的一次性实例
  6391-6394，电池 39-48 共 380 行逐行对拍（`cmp3.py` 自己报的行数，不是文件行数），两侧 `DIFF=0`）：
  - `BITCOUNT`：查键 → 类型 → 计数区间文法 → 整数文法。
  - `GETRANGE`：整数文法 → 查键 → 类型 —— 和 `BITCOUNT` 正好反着，所以"抽一个共用前置检查"
    的写法必然在其中一支上红。
  - `SETBIT`：offset 文法 → bit 文法 → 类型。
  - `BITPOS`：arity → bit 文法 → bit 是否 ∈ {0,1} → 查键 → 类型 → `argc>5` 的 syntax →
    start/end 整数文法；`BITPOS k 2 …` 回 `-ERR The bit argument must be 1 or 0.`，
    越界下标按参考实现的形状折叠（`MAX_BIT_OFFSET = 1L << 32`）。
  - `BITOP`：arity（`argc<4`）→ 操作名 syntax → `NOT` 只能单源那一句话 → 各源类型检查。
    `BITOP FOO d k` 与 `BITOP SET d k` 都是 `-ERR syntax error`；
    `BITOP NOT d k1 k2` 是 `-ERR BITOP NOT must be called with a single source key.`
    （`Not` / `nOt` 同命）。
- **BITOP 是这一族里唯一不数位的成员，它按字节答复**：`battery45:16` 那行
  `bitop and b45n b45a b45b`（`b45a=hello`、`b45b=world`）参考实例回 `:5`，不是 `:40`。
  `GETBIT / SETBIT / BITPOS / BITCOUNT` 都按位说话，第一版就是按那一族类推写了 `max * 8`，
  纠正过来之前判红 **23 行**（电池 45/46/47 里回数为正整数的那 23 行 `BITOP`；
  另有 5 行是 `:0`，`×8` 不动它，所以那 5 行不构成证据）。
  现在这条既有真值行、也有把 `*8` 种回去的变异探针（`expected: <:5> but was: <:40>`）。
- 语义面实测到的形状（逐条钉在 `bitopRepliesBytesAndFollowsTheMeasuredPrecedence` 里）：
  一个字节内位序是 MSB 优先；短源右端补零、结果长度取最长源；最长源长度为 0 时
  **删掉**目标键而不是留一个空串（`BITOP AND d <missing>` → `:0` 且 `EXISTS d` → `:0`）；
  非零长度但全零的结果照样写进去；目标键无条件被覆写且**不做类型检查**（目标键是 list 时
  `BITOP AND list-dest …` 仍回 `:5`，随后 `TYPE` 回 `+string`、`LRANGE` 才回 WRONGTYPE，
  `battery46:33`）；某个源类型不对则在写任何东西之前就中止（目标键留着原值）；
  目标键的 TTL 被清掉（`PSETEX` 之后跑 `BITOP` → `TTL` 回 `:-1`）。
- 顺手抓到一条**不会红的**缺陷：`SETBIT` 从加进来那天起就不在 `WRITE_COMMANDS` 里 ——
  值进内存、当场 `GET` 得到、测试全绿，只有进程换过一代之后才看得出 AOF 里一行都没有、
  停机快照里也没有。`SETBIT` 与 `BITOP` 现在都在这张表里。
- `bumpWatchedKeys` 的通用形状是"键名在下标 1"，对 `BITOP` 不成立（下标 1 是
  `AND/OR/XOR/NOT`，被改写的目标键在下标 2）：照通用形状走，会给一个名叫 `AND` 的键抬版本号、
  真正改掉的那个键不动。现在 `BITOP` 单独一支，只 bump 目标键 —— 于是 `WATCH` 一个源键、
  别人对它跑 `BITOP`，`EXEC` 照样交出结果（源是只读的），而 `WATCH` 目标键必须中止。

#### Stream ID 的文法：参考实例根本不认这个类型，所以权威换成上游源码的行号

- **先把依据的档次说清楚**：Stream 这一族在钉住的参考实例上量不到。redis-server 4.0.9 早于
  Stream（那是 5.0 新增的类型），250 实测 `battery49` 整批里每一条
  `XADD / XRANGE / XREAD / XINFO` 都回 `-ERR unknown command 'XADD'`。所以本节每一行的右边
  都不是对拍读数，而是上游 `t_stream.c`（redis 5.0.14）那一行<b>说</b>的答案；原文留在
  `~/.cache/zcache_gauges/t_stream_5014.c`，两张测试表里逐行写了对应行号。
  凡是只能靠"我以为 C 会这么解析"来定的写法，一条都没收进来。
- 换掉的头一样东西是拿 `Long.parseLong` 读 ID：`XADD k 1-1-1 f v` 抛出的
  `NumberFormatException` 被 `handle()` 兜成
  `-ERR internal error: For input string: "1-1-1"` —— 一个客户端语法错被报成服务器内部错。
  现在八个入口（XADD / XRANGE / XREVRANGE / XDEL / XACK / XREAD / XREADGROUP / XGROUP CREATE）
  只认 `StreamIdFormat` 一处，非法一律回上游 :1205 那句原文，端到端那一支把
  "回复里不得出现 `For input string` / `java.lang` / `internal error`" 钉成了兜底断言。
- 两段都是 **uint64 而不是 int64**，上游 `string2ull`（:1147）因此先试 `string2ll`、
  失败才退到 `strtoull`（:1156）。这一退让两族的接受集不对称，三组对照全在表里：
  `05-1` / `+1-1` / `" 1"` 收（`string2ll` 不吃前导零、正号、空白，`strtoull` 吃），
  `1 ` 否（:1157 要求把整串吃光）；`18446744073709551615-1` 收、
  `18446744073709551616-1` 否（ERANGE）；回绕那一对最阴 —— `1--1` 否但 `1- -1` 收成
  `1-18446744073709551615`：前导那个空白让 `string2ll` 先失败，于是走到 `strtoull`，
  而 C 的 `strtoull(" -1")` 交回 `2^64-1` 且**不**置 errno。原样保留，没有"顺手修正"。
- 长度闸与数值闸是两道：`char buf[128]`（:1175-1176）只看字符数。127 个零加一个 `1`
  要收，同样的数字撑到 128 个字符就否；反过来 `92233720368547758081-0` 长度合法、
  数值超 uint64 而否。写成 128 个 `1` 那种用例分不出这两道闸，一开始就写错了。
- `strict` 一位不能合并：`-` / `+` 在 XRANGE / XREVRANGE 这类位置就是最小与最大 ID
  （:1183-1190），在 XADD（:1276）/ XDEL（:2425）/ XACK（:1985）/ XREAD（:1549）/
  XGROUP CREATE（:1869）这类位置是非法 ID（:1179-1180）。缺省 seq 还不对称：起点 `1`
  是 `1-0`，终点 `1` 是 `1-18446744073709551615`（:1356-1357），所以 `XRANGE k 1 1`
  要给出这一段的全部条目。顺带纠了我自己的一个想当然：`-` 是"最小 ID 本身"而不是
  "第一条之前"，而 `0-0` 根本存不进来（:1293），于是 `XRANGE k - -` 是空集。
- 比较必须按无符号：`StreamEntry.compareIds` 原来用有符号 `long` 比，于是
  `18446744073709551615-…` 这条**最大**的 ID 排在所有条目之前 —— `XRANGE k 1-2 +` 把它
  放到队尾。同一个符号错在 `Stream.generateId()` 里更硬：显式写过最大 ID 之后，
  `now > lastTimestamp` 会判"当前时间比上一条新"，自动 ID 于是回退到流内已有序号之前。
- 本机实测（`battery50`）抓到两条不是文法、而是"根本没判"的缺陷：
  `XREADGROUP GROUP g c1 STREAMS k abc` 回的是**整段历史**（第 21 行），
  `XREADGROUP … STREAMS k $` 同样回整段历史（第 20 行）—— 因为这一位以前一个字都不校验，
  坏 ID 在存储层退化成 `0-0`。`$` 与 `>` 在上游是在数字文法之前单独收下的两个特例
  （:1518、:1535），而且各自只在一个命令上合法、拒绝它们各有一句专门的话
  （:1520、:1537）；用语法错去回答"用法不对"会把客户端指错方向，这两句现在照抄原文。
- `XDEL` 要先把**每个** ID 都判一遍再动手删（:2420-2427 那段 sanity check 的注释写的就是
  "命令因为中途一个非法 ID 只执行了一半"这种事）：以前 `XDEL k 1-1 not-an-id` 删掉 `1-1`
  才报错。现在同一支的判据是"回错误且 `XLEN` 一条没少"。
- 交回客户端的写法由数值反推（上游 `addReplyStreamID` 的形状），所以 `05-1` echo 成 `5-1`、
  `+1-1` echo 成 `1-1`、`7` echo 成 `7-0`；二次解析落在同一点上，这一条也钉了。
- 七支具名变异全部点名判红，跑完按字节还原（md5 对账）：
  摘掉 `strict` 分支 → `parse("-", strict=true) ==> expected: <null> but was: <[J@…>`；
  摘掉 `strtoull` 退路 → `parse("05-1", 0, false) ==> expected: <5-1> but was: <null>`；
  比较改回有符号 → `expected: <true> but was: <false>`；
  摘掉 XADD 的 `0-0` 闸 → `expected: <-ERR The ID specified in XADD must be greater than 0-0> but was: <0-0>`；
  摘掉 XREADGROUP 的校验 → `expected: <-ERR Invalid stream ID …> but was: <*1>`；
  XDEL 不预检 → `expected: <-ERR Invalid stream ID …> but was: <:1>`。
  第七支不是编出来的题：写这一版时我真的把 XREAD 那一处填成了 `true`，
  于是 `XREAD … $` 回了 XREADGROUP 那句、`XREAD … >` 反倒把整条流交出去 ——
  最后一支（把 XREAD 的 strict 位填反）抓的就是这个错，红在
  `">" 只在 XREADGROUP 上合法（:1535-1539）…` ==> expected: <true> but was: <false>`。

#### XADD 的单调性闸：等于或小于表顶不写，ID 用尽之后连 `*` 也不写

- 上一支把 ID 的**文法**收住了，但没有管**顺序**，于是本机 `battery50` 量到的是这样一条流：
  先写 `1-1`、`1-2`、`1-3`，再写 `1-2` 和 `1-1` 都成功 —— `XLEN` 从 3 变 5，
  `XRANGE - +` 交回 `1-1, 1-2, 1-3, 1-2, 1-1`。同一个 ID 出现两次、且不再是升序表，
  "按 ID 续读"这件事就失去意义了（`XREAD` 那一支的语义下一支才接）。
- 现在两道闸都排在写入之前，两句文案照抄上游：`:1315` 的
  `The ID specified in XADD is equal or smaller than the target stream top item` 与
  `:1304` 的 `The stream has exhausted the last possible ID, unable to add more items`。
  判序也照上游：`:1304` 在 append **之前**、`:1315` 的 EDOM 在 append **之内**，
  所以表顶已经用尽时，哪怕客户端写的是 `1-1` 这种"本来就该回前者"的 ID，回的仍是"用尽"那句
  （变异探针 M9 就是把两句颠倒，红在这一行上）。
- 表顶是**迄今写过的最大 ID**（上游的 `s->last_id`），不是"最后一条条目"：`XDEL` 删空、
  `XTRIM MAXLEN 0` 裁空都不把 ID 空间退回来。这一条单独立了一支探针（M11 把表顶改成
  "读 `entries` 的最后一条"），红在"流都空了也不能把 `5-5` 重发一遍"。
- 无符号比较在这里第二次咬人：把闸的 `StreamIdFormat.compare` 换成有符号（M10），
  `XADD k 18446744073709551615-18446744073709551615` 会被当成"比表顶 `1-2` 还小"而拒掉，
  连带 `XRANGE` 那一支少了条目 —— 两支同时判红。
- 被拒的 XADD 不留键：语法错、`0-0`、等于或小于表顶三种都在建键之前返回，
  `EXISTS` 回 `:0`（上游 :1293 那段注释担心的就是"建了流又插不进去"的空键）。
- `*` 走的还是自增路径，但表顶用尽之后同样回"用尽"那句 —— 因为 `:1304` 的检查与 ID
  是不是显式无关。这一条是实测行（`battery51:8`、`battery51:30`）。
- 这一支另跑 4 支具名变异，全部点名判红、按字节还原：
  M8 摘掉"等于或小于表顶"闸 → `等于表顶 的写法不能写进去 ==> expected: <-ERR The ID specified in XADD is equal or smaller than the target stream top item> but was: <1-1>`；
  M9 两句闸序颠倒 → `这一条既小于表顶、又落在 ID 用尽之后 —— 上游先回用尽（:1304 早于 append） ==> expected: <-ERR The stream has exhausted…> but was: <-ERR The ID specified in XADD is …`；
  M10 表顶比较换回有符号 → `uint64 的最大值本身是合法 ID…==> expected: <18446744073709551615-18446744073709551615> but was: <-ERR …equal or smaller…>`（同一支还把 `XRANGE` 那一条拖红）；
  M11 表顶改读"最后一条条目" → `流都空了也不能把 5-5 重发一遍 ==> expected: <-ERR …equal or smaller…> but was: <5-5>`。

#### XREAD / XREADGROUP 的那个参数是"位置"，不是"范围起点"

- **依据档次先说清楚**：这一族仍然在钉住的参考实例上量不到（redis-server 4.0.9 连 `XADD` 都不认，
  250 实测每条 stream 行都回 `-ERR unknown command 'XADD'`）。本节每一行的右边全部来自
  上游 `t_stream.c`（5.0.14）的行号，`battery52` 那 47→55 行是**本机改动前后的自比对**
  （`zreplay.py` 打的是我们自己那台服务器），不是与参考实例的对拍。改前 47 行里 **17 行答错**，
  改后逐行与源码推出来的期望一致。
- **位置排他**：`t_stream.c:1560` 那一行的注释就是 `/* ID must be greater than this. */`，
  而交给范围查询的 start 是 `streamIncrID` **之后**的值（:1602-1603）。于是
  `XREAD STREAMS k 1-1` 交回的是 `1-2` 往后，不含 `1-1` 自己；改前把 `1-1` 也交出去，
  客户端拿着这个答复再问一次 `1-1`，就永远停在同一条上。
- **"没有条目可交"时整个键不点名**：:1586-1593 先问 `if (s->length)`，再拿**存活条目的最大值**
  （`streamLastValidID`，:1590）与位置比；一个键都没点名才是 `*-1`（:1664）。为此
  `Stream` 新增 `lastValidId()`，与上一支的 `lastId()` 明确分家 —— XDEL 掉最大那条之后
  前者退回去、后者不许退（拿 `lastId()` 去判这一问，会给客户端点名一个列表为空的键，
  变异探针 M20 就是这个错法，红在"`XREAD … 2-0` 之后已经没有活着的条目"）。
- **后继会回绕**：`streamIncrID`（:77-89）两段都到顶时回绕成 `0-0`，那个函数没有返回值，
  不存在"停在 MAX-MAX"这一说。XREAD 这一侧观察不到它（:1591 那道闸先把它挡住），
  XREADGROUP 读历史**没有**那道闸，所以 `XREADGROUP … MAX-MAX` 交出的是整个历史。
  这一支只有纯函数层（`StreamIdFormatTest.successorCarriesAndWrapsLikeStreamIncrID`）
  和历史那一侧钉得住，M15（钳顶）与 M15b（丢掉 ms 进位）两支都在那里判红。
- **`$` 是"这条流当前的位置"**：:1527-1533 取的是 `s->last_id`，键不在时才折成 `0-0`。
  改前 `$` 等于整条流。表顶不因 XDEL 后退，所以 `$` 也停在那儿 —— 而"表顶不后退"这件事
  只影响 `$`，不该顺手把 `XREAD … 2-0` 那一问也抬高（两条都在 `xreadPositions…` 里各钉一行）。
- **XREADGROUP 带明确 ID 读的是这个消费者自己的 PEL**：:981-984 把查询来源整个换掉，
  :1083-1120 只按消费者本地 PEL 的序遍历。这一换带来五件各自要钉的事：
  1. 别的消费者领走的东西不会出现在这里（改前把一个从没领过任何条目的 `c2` 的历史答成整条流）；
  2. 位置同样排他 —— `raxSeek(">=", 加过一的位置)`（:1093），所以位置写自己手上那条也交不出它；
  3. **空历史也要点名这个键**（:1596-1598 的 `arraylen` 无条件自增），是 `[[k, []]]` 不是 `*-1`；
  4. PEL 上还压着、条目却已被 XDEL 的那条，交回 `[id, nil]`（:1101-1109），不是悄悄少一条
     —— 少一条等于让客户端以为从没领过它，那条就永远不会被重新处理；
  5. 这次读要顺手把消费者登记出来（:1610-1612 的 `streamLookupConsumer(…, SLC_NONE)` 会建，
     :1745-1757），于是"读一份空历史"之后 `XINFO CONSUMERS` 看得见 `pending, :0` 的它。
- 同一句 `STREAMS` 里把同一个键写两遍是**两问**，不是"后者覆盖前者"：上游那一段是数组不是查表。
  改前位置装在 `LinkedHashMap` 里，重复键被并成一个。
- `COUNT 0` 在上游是"不限"，不是"一条"（:1441 把负数折成 0，:1063 是
  `if (count && count == arraylen) break;`）。这一条我一开始记的是"上游钳到 1"，
  读了那两段才把自己的假设否掉 —— 代码本来就是对的，改的是测试里那句错的注释，
  没有为了迁就注释去动实现。
- **两个特例各只属于一个命令，且用错了命令有自己的一句话**（:1519-1525 的 `$`、
  :1536-1541 的 `>`），并且是 `goto cleanup`：**整条命令作废**，同一条里后面那些合法位置也不交。
  这两句在上一节里其实已经被钉过 —— 但只钉了**前缀**（`startsWith("-ERR The $ ID is meaningless"…)`），
  于是句子的后半段、以及"作废是整条作废还是只作废那一个键"这两点结构上无人可验。
  `eachSpecialPositionIdBelongsToOneCommand` 把它们换成逐字 `assertEquals`，并补上
  "后面那个位置合法也不许先交结果" 与 "被作废的两次连消费者都不该登记出来"，
  同时带阳性对照（`$` 在 XREAD 上、`>` 在 XREADGROUP 上都要被收下，否则负判据是空跑）。
  探针 M24 就是把 `$` 那句的后半段整段删掉：只有前缀那把尺时它必然活下来，现在点名判红。
- 本轮另跑 **18 支具名变异，18 支全部点名判红、按字节还原并 md5 对账**（脚本
  `~/.cache/zcache_gauges/read_mut.py`，快照 `read_snapshot/`，日志 `read_mut_all.log`
  与 `mut_M2*.log`）：摘掉后继那一加、把 `$` 折成 `0-0`、摘掉"存活条目比位置大"那道闸、
  那道闸改用不退的表顶、空历史不点名键、已删条目渲染成空字段、历史位不加后继、
  历史起点改成排他、PEL 不分消费者、历史改从流里扫、`successor` 钳顶 / 丢进位、
  `lastValidId` 只比毫秒段、XREAD 的 strict 位填反、两个特例分支各摘一次、
  `$` 那句只留前半段（M24）、把"整条作废"降级成"只作废那一个键"（M25）。
  M6（XREAD 的 strict 位填反）是上一节那支探针的复跑，这一轮把它放到两个测试类上连跑，
  三处点名判红：旧的那句"`>` 在 XREAD 上要回原文"，加上这一节新用例的"`$` 在 XREAD 上合法"
  两处 —— "`$` 这一侧看得见"只有新用例给得出。
- **有意没做的一条**：读历史时上游会给重新交出的条目抬 `delivery_count` / `delivery_time`
  （:1111-1113）。我们的 PEL 是 `Map<String, String>`（条目 ID → 消费者名），这两个值没有任何读者，
  所以这一支不引入"写了没人读"的元数据；它归在下面的已知边界里。

#### stream 族的取键那一问：同一个键名下并存两种类型的最后一扇门

- **这一扇门是谁开的**：1.3.5 给另外五族补了类型闸门，1.3.6 的 `RENAME` 又拆掉一类生产者，
  stream 一族是**漏网的那一族** —— `battery53:4` 实测 `SET t53:str hello` 之后
  `XADD t53:str 1-1 f v` 回 `"1-1"` 成功，紧接着 `GET` 仍回 `"hello"`（`:5`）、`XRANGE`
  交出那条 stream 条目（`:6`），而 `TYPE` 只报其中一种（`:3` `+string`）。同一个键名下
  两份数据并存。修之前整个 stream 族走的是 `streams()` 自己那张表，从没过 `store.typeOfDb`。
- **上游的判序是逐点定的，不是一句"入口处查一下"**：`checkType(OBJ_STREAM)` 在十一个位置
  各回各的 —— XRANGE :1377（排在 ID 与 COUNT 解析**之后**，所以坏 ID 先说话）、
  XLEN :1402、XREAD/XREADGROUP :1500（在 per-key 的 ID 循环**里**，`o && checkType` 之后
  `goto cleanup` ⇒ **整条命令作废**，不是只作废那一个键）、XGROUP :1830（只在 argc≥4 时问）、
  XACK :1971（排在 :1977 那道"键或组不在就回 `:0`"的 bail **之前**）、XPENDING :2043（排在
  :2047 的 NOGROUP **之前**）、XDEL :2417（排在 :2425 的 ID 体检**之前**）、XTRIM :2462
  （排在选项解析之前）、XINFO :2554、XADD 走 `streamTypeLookupWriteOrCreate` :1128-1138
  （调用点 :1300，即 MAXLEN/ID 都解析完 :1255-1279、arity :1285、`0-0` bail :1293 之后，
  且键被占着时**不会顺手把流建上去**）。`CommandHandler.streamTypeConflict(key)` 是这一问的
  唯一实现，11 处按上面的位置各插一处（`CommandHandler.java` 的 :2491 / :2535 / :2567 /
  :2592 / :2617 / :2670 / :2734 / :2789 / :2838 / :2865 / :2903）。
- **XACK 那一问顺手更正**：上游 :1975-1977 的注释就是 `No key or group? Nothing to ack` ——
  键不在**或组不在**都回 `:0`，不是 `-NOGROUP`。我们本来就回 `:0`，所以这一条**不是缺陷**。
  上一节把它记成"上游那一句同样是 `-NOGROUP`，与 NOGROUP 那支一起改"，还给了一个
  `battery53:26` 的行号 —— 那一版总共 25 行，第 26 行不存在，那句"上游同样是"我也没读过源码。
  这一版按 :1971/:1977 的判序把它显式落地，并量了它的前后：`XACK t53:ok nosuchg not-an-id`
  交 `:0`（`battery54:35`，ID 根本没解析，因为 :1985 的循环在 :1977 之后）。
- **改后 46 行实测**（`battery54`，本机 jar，`wrote=46 lost=none`；前 25 行与 `battery53`
  逐行同命令同序，用来做前后自比对）：`:2 :4 :6 :10 :11 :13 :14 :15 :25 :26 :27 :28 :29 :31 :32 :33 :34`
  十七行现在是 `-WRONGTYPE Operation against a key holding the wrong kind of value` 原文；
  `:5` `GET t53:str` 仍是 `"hello"`、`:40` `HGET t53:h f` 仍是 `"v"`（闸门一个字都不改写）；
  `:30` `XRANGE t53:l not-an-id +` 仍报坏 ID（闸在 :1377 那个位置，没抢答）；
  `:36` `XADD t53:h 0-0 f v` 仍报 `0-0` 那一句、`:37` `XADD t53:h bad-id a 1` 仍报坏 ID
  （闸排在 :1293/:1276 之后）；`:38` `XADD t53:h 1-1 a` 仍是我们自己的
  `XADD needs at least one field value pair` —— **这一条与上游仍然不同**，归下面的已知边界；
  `:34` 一条 `XREAD STREAMS t53:ok t53:h 0-0 0-0` 只回**一个** `-WRONGTYPE`，就是"整条作废"的形状。
- 回归落在真实服务器上：`streamFamilyHoldsTheSameOneTypeInvariant` 一个方法做四件事。
  ① **阳性对照**：一枚真流键上 18 处 stream 调用（12 个命令名，含 `XREVRANGE`、三个
  `XINFO` 子命令与四个 `XGROUP` 子命令）各自必须成功（`XLEN` 回 `:1`、`XRANGE` 交出
  `[[1-1, [a, 1]]]`、`XTRIM … MAXLEN 10` 回 `:0`、`XACK` 回 `:1`、`XREAD` 交出整个键那份列表），
  否则"不许回 WRONGTYPE"是空跑；
  ② 五种占位类型 × 18 支 stream 命令逐条 `assertEquals` 原文（新增 `expectWrongType`），
  不是 `startsWith`；③ 闸门不改写字节：`GET` / `HGET` / `LLEN` / `SISMEMBER` / `ZCARD`
  读数原样；④ 判序与作废面各钉一行：坏 ID 先说话的那一支、`XDEL` 的闸在 ID 体检前那一支、
  `XTRIM … MINID 3` / `XPENDING … nosuchg` / `XREADGROUP … $` 都要错成 WRONGTYPE 而不是各自的
  语法句 / NOGROUP 句、`XACK` 不存在的组回 `:0`、多键整条作废 + 两个好键的对照
  （`[[k, [[1-1, …]]], [k, [[1-1, …]]]]`，证明"只作废坏键"不是恰好把好的也吞了）。
- **18 支具名变异，18 支全部点名判红**（脚本 `~/.cache/zcache_gauges/gate_mut.py`，
  快照 `gate_snapshot/`，日志 `gate_*.log`；还原只从本次快照 `cp` 回来并 md5 对账，
  不动 git —— 基线是 HEAD，而这些文件上还有我没提交的改动，`git checkout` 会一起抹掉）：
  G1-G11 逐个摘掉 11 处调用点，H1 让本体恒回 `null`，H2 放 string 漏过闸门，
  H3 把 `== NONE` 判反，O1 把 XDEL 的闸挪到 ID 体检之后，O2 把 XRANGE 的闸提到 ID 解析之前，
  O3 删掉 XACK 的"组不在回 `:0`"，O4 把 XREAD 循环里的 `return conflict` 换成 `continue`。
  每支的红都点名到具体判据（例：G10 红在 `XPENDING sem:ty:string g` 期望 WRONGTYPE 而回
  `-NOGROUP …` —— 摘掉闸后错成"组不在"而不是错成没反应；H3 红在正常流键上的 `XADD` 被误伤）。
  **一处取证限制要说清**：O4 只留下"单键那一行红"这一条点名判红 —— 该测试方法在第一条断言
  失败处就停，它下面那条"多键整条作废"的断言在这一支探针下从来没执行到（`gate_O4.log` 里
  `AssertionFailedError` 计数为 1）。这一支证明了"降级成只作废那一个键"会被看见，
  没证明看见它的是哪一条断言。
- **反方向这一版没做，而且是量出来的**：stream 键对键空间**仍然不可见**。
  `MemoryStore.DataType` 只有 `{NONE, STRING, HASH, LIST, SET, ZSET}`，`typeOfDb` 从不问
  `StreamStore`，`streams()` 只被 stream 自己的 handler 引用 —— 于是 `battery54:41`
  `TYPE t53:ok` 回 `+none`、`:42` `EXISTS` 回 `:0`、`:43` `DBSIZE` 回 `:2`
  （那两枚是 `t53:l` 与 `t53:h`，有条目又有组的 `t53:ok` 一分不占）、`:44` `DEL t53:ok` 回 `:0`
  而 `:45` `XLEN` 仍回 `:1`、`:46` 还能继续 `XADD`。`TYPE` 报 `+none` 而 `XLEN` 报有条目，
  正是"同一个键名两种视图"的另一半。这一半要改的是 `MemoryStore` 的键空间而不是
  `CommandHandler` 里加一句话，所以单独一票做；`streamTypeConflict` 的文档注释里写明了
  前提（它问的是"这枚键名被别的类型占着吗"，不是"这是不是 stream"）。

### Added
- `RedisServer.serverScope()`：只读拿到本台那一份，测试与嵌入式据此判断作用域边界。
- `StreamIdFormat`（z-cache-common）：stream ID 的唯一文法（uint64 两段、`-` / `+` 两种位置、
  `missing_seq`、`compare` 无符号、`format` 规范化），八个命令入口共用；
  `StreamIdFormatTest` 33 行判据（每行标上游行号），
  `RedisServerProtocolSemanticsTest.streamIdsFollowTheUpstreamGrammarOverTheWire`
  从协议那一侧再走一遍并兜住"不得漏出 JVM 文本"。
- `Stream.lastId()`（表顶 = 迄今最大 ID，删空 / 裁空都不退回）与 XADD 的两道单调性闸；
  `RedisServerProtocolSemanticsTest.xaddRejectsIdsAtOrBelowTheStreamTop` 把三种"不升"
  （等于表顶 / 同 ms 更小 seq / 更小 ms）、两句文案的判序、删空裁空后表顶仍在、
  被拒不留键，以及"最大 ID 写进去之后 `*` 也回用尽"各钉一行。
- `StreamIdFormat.successor(ms, seq)`：上游 `streamIncrID`（:77-89）那一位，含 ms 进位与
  MAX-MAX 回绕成 `0-0`。XREAD 与 XREADGROUP 的历史位共用它（上游也是同一处 :1603）。
  `StreamIdFormatTest.successorCarriesAndWrapsLikeStreamIncrID` 钉纯函数层，
  历史侧的回绕由协议用例钉（XREAD 那一侧结构上看不见，见上一节）。
- `Stream.lastValidId()`（上游 `streamLastValidID`，全删光时返回 null 而不是 `0-0`，
  毫秒段相同还要比序号段）与 `ConsumerGroup.pendingIdsOf(consumer)`（这个消费者手上
  没 ACK 的条目，按 ID 升序）。
- `StreamStore.xreadgroup(...)` 拆成 `xreadgroupNew(...)`（按组的位置投新条目并记账）
  与 `xreadgroupHistory(...)`（只读这个消费者的 PEL；返回 null 专指"键或组不在"，
  与"空历史"分开，因为这一版还不回 NOGROUP）。
- `CommandHandler.streamEntryReply(id, fields)`：三处 stream 条目的渲染收成一处，
  `fields == null` 就是上游 :1109 那个 `addReply(c, shared.nullmultibulk)`（`$-1`），
  不是空字段列表。
- `RedisServerProtocolSemanticsTest.xreadPositionsAreExclusiveAndHistoryComesFromTheConsumerPel`
  把上面这一整套按网线上的原文逐行钉住：严格大于、`COUNT 1` / `COUNT 0`、seq 用尽进 ms、
  MAX-MAX 两个方向（XREAD 交出 `*-1`、历史交出全份）、`$` 的三种落点、空历史仍点名键、
  消费者之间的 PEL 隔离、ACK 之后退账、位置排他的两端（`1-0` 交出 `1-1`、`1-1` 不交自己）、
  重复键名是两问、XINFO 看得见空历史建出来的消费者、被 XDEL 那条交回 `[id, nil]`。
- `RedisServerProtocolSemanticsTest.eachSpecialPositionIdBelongsToOneCommand`
  钉 `$` / `>` 那两句原文与"整条命令作废"，两头都带对照。
- `RedisServerProtocolSemanticsTest` 增加 2 条端到端回归（`streamKeyspaceIsScopedToOneServerInstance`
  两头都量：B 读不到 A 的流，同时 A 读得到自己的流；
  `serverWithoutDataDirDoesNotDisableOtherServersPersistence` 先钉"带 dataDir 这台本来能 SAVE"
  再让第二台启动）。
- `renameReplacesTheDestinationInsteadOfMergingIntoIt`（hash / list / set / zset 四种源键各自
  "dst 有旧值"的形态 + 跨类型 + `RENAMENX` 看得到集合键）与
  `renameMovesTheSourceTtlToTheDestination`（源键 TTL 搬过去、被顶掉的旧 hash 读不到）。
- `ZCacheClientIntegrationTest` 不再"探测 6379 上有没有人，没有就整类跳过"——本仓库没有任何
  东西会在 6379 起服务，于是这 12 条用例从来没执行过（surefire 报告里 `skipped=12`），
  `ZCacheClient` 的公开 API 对真实服务器的往返是零覆盖；反过来万一本机真有一个 Redis，
  它们会拿别人的实例当被测对象并把 `FLUSHDB` 打进人家的库。现在类自己起一台（临时端口，
  `@AfterAll` 停掉），并把四条恒真判据钉成实数：`DEL` 回 2、`EXISTS` 回 2、`EXPIRE` 回 1、
  `INCR`/`DECR` 回 1/0、5 线程 × 20 次"写完立刻读回"要 100/100 全部对上。
- 两个服务器测试类的 `startAndWait`：启动线程已经死了就立刻报" died before listening on …"，
  不再空转到 8 秒超时后甩一句"did not start listening"。`freePort()` 探到的端口在被 bind 之前
  可能被内核分给别的连接（本轮 100 多次启动里遇到 1 次，`BindException` 只落在那个守护线程的
  栈里），这属于量具问题，判据要能自己说清是哪一类。
- 摘掉绑定的变异探针（`RedisServer` 传 `null` scope）：跑整个 core 模块（不是单个测试类）时
  **12 条命名判红** —— 两条新 Stream / 持久化作用域回归、原来的 pub/sub 隔离回归、
  `SLOWLOG` 接线、`CLIENT LIST`、四条 Stream 命令用例，以及 `RedisServerLifecycleTest` 里
  AOF 三代重放 / 定时快照 / SAVE 全库 TTL / 无 dataDir 起 Stream 那四条。
  `RENAME` 这一版另跑 5 支（摘 dst 整表清理 / 源键判据换回 `keyTypeMaps` / 不删源键 /
  TTL 不搬 / `deleteEveryType` 退回"第一条命中就返回"），**5 支全部点名判红**。
  探针跑完按字节还原（md5 对账），脚本留在 `~/.cache/zcache_mut/`。
- 位族五支的回归全部落在真实服务器上，不在 `CommandHandler` 的单元测试里：
  `RedisServerReferenceParityTest` 新增 5 条
  （`bitcountLooksUpTheKeyBeforeGrammar` / `bitcountMatchesTheMeasuredTruthTable` /
  `getbitAndSetbitFollowTheMeasuredGrammar` / `bitposMatchesTheMeasuredPrecedenceAndFolding` /
  `bitopRepliesBytesAndFollowsTheMeasuredPrecedence`，该类现在 21 条）。
  每条期望串都是 250 参考实例那一行的原文，**没有一条是手算的**——
  `BITCOUNT` 手算错过两次（0x6c 是 4 位、0x61 是 3 位），之后专门补了 `battery48`
  只为准许把 `BITOP OR bit:dest bit:pad bit:src` 之后的 `BITCOUNT` 取成实测的 `:22`。
- `RedisServerProtocolSemanticsTest.bitWritesSignalOnlyTheKeysTheyActuallyChange` 四支：
  `WATCH` 目标键 + 别人 `BITOP` → `EXEC` 中止；`WATCH` 源键 + 别人 `BITOP` → 结果照交；
  `WATCH k` + 别人 `SETBIT` k → 中止；外加一支"改的是无关键"的阴性对照，
  否则"一个键都不 bump"也能把前三支糊过去。
- `RedisServerLifecycleTest.bitWritesAreJournaledAndReplayed` 跨代际：第一代只写不 SAVE，
  删掉 `dump.rdb`，第二代只许从 AOF 把 `GETBIT` / `STRLEN` / `BITCOUNT` / `GETRANGE`
  四个读数原样读回来 —— 这是 `SETBIT` 漏出 `WRITE_COMMANDS` 那条缺陷唯一会红的地方。
- 位族具名变异 4 支，**4 支全部点名判红**：`BITOP` 的答复改回 `max * 8`（红在 `:5` vs `:40`）、
  摘掉 `NOT` 单源那句话（红在 `battery45:14`，且是"错成 WRONGTYPE"而不是错成没反应）、
  给目标键也加类型检查（红在 `battery46:33`）、摘掉 `bumpWatchedKeys` 的 `BITOP` 分支
  （红在"WATCH 的键被 BITOP 改写，EXEC 必须中止"）。按字节还原 + md5 对账，
  脚本 `~/.cache/zcache_gauges/zmut_bitop.sh`。

- 计数只认实测（`tally.py` 从 surefire 报告聚合，空运行会硬 FATAL 而不是打"0 例全绿"）：
  `mvn clean test` 全量 **358 + 378 + 134 + 2 = 872 例全绿，0 failures / 0 errors / 0 skipped**
  （取键闸门这一支加进 1 条协议层用例；1.3.6 内上一次记录是 358 + 377 + 134 + 2 = 871，
  再往前是 357 + 374 + 134 + 2 = 867；那节写的"832"与"96 + 340 + 133 + 2 = 571"都是旧刻度，一并留档）。
- 这类跨实例作用域缺陷只在"整模块连跑"的形态下现形，所以按 `-Dsurefire.runOrder=random`
  把 common+core 连跑 9 次（3 + 6 两批）：**7 次 324 + 372 全绿，2 次不是**。执行顺序确实
  变了（三批的 md5 `355a02d4…` / `0b99054e…` / `911368ea…` 互不相同，不是只传了个开关）。
- 那 2 次红的归因只做到一半：6 连跑第 4 次红在
  `saveSnapshotsEveryDatabaseAndTheirTtls → startAndWait` 的"线程死了还没开始监听"，
  端口探测与 bind 之间被抢占是**嫌疑**而不是证据；3 连跑第 2 次当时脚本没留住失败名，
  只剩一个 `Errors: 1`，无法归因。为此三个服务器测试类的 `startAndWait` 现在把那条
  服务器线程带回来的异常一起报出来（旧版只留下一段没人在读的栈），下次红由异常自己说。
- `CommandHandler.streamTypeConflict(key)`：stream 一族"这枚键名被别的类型占着吗"的单一实现，
  按上游的 11 个位置各插一处（XADD / XLEN / XRANGE+XREVRANGE / XDEL / XTRIM / XREAD /
  XREADGROUP / XGROUP / XACK / XPENDING / XINFO，`CommandHandler.java:2491,2535,2567,2592,2617,2670,2734,2789,2838,2865,2903`）。
  位置本身是判据的一部分，所以没有做成一处统一的入口检查。
- `RedisServerProtocolSemanticsTest.streamFamilyHoldsTheSameOneTypeInvariant`：真流键上 18 处
  stream 调用的阳性对照 + 5 种占位类型 × 18 支命令（90 条）的 `assertEquals` 原文扫描
  + "闸门不改写字节" + 判序与整条作废面。配套 `expectWrongType` helper（不再用 `startsWith`）。
- 取键闸门的具名变异脚本 `~/.cache/zcache_gauges/gate_mut.py`（18 支，快照 `gate_snapshot/`，
  还原只从本次快照 `cp` + md5 对账，互斥锁 `mut.lock` 带 pid 校验）。

### 已知边界（这一版没动，说清楚）
- RESP3 / `HELLO`、`EVAL` / `EVALSHA` / `SCRIPT` 依旧没有服务端实现，客户端 `DistributedLock`
  因此仍走"GET 校验后 DEL"的非原子路径。
- Stream 不参与 RDB/AOF；只有 String 键的 TTL 进快照（集合键连 `EXPIRE` 都还不支持，
  所以 `RENAME` 也只搬得动 String 的 TTL）。
- Stream 这一族的**文法**、**XADD 的单调性**、**XREAD / XREADGROUP 的位置语义**、
  **取键那一问的类型闸门**这一版都收了。剩下的读侧边界全在**答复层**（文案与形状），
  下面每一条都标清依据档次：要么是本机现状实测的行号，要么是上游 `t_stream.c` 的行号，
  两者都不写的就是没量过 —— 不许照抄成事实。
  - **NOGROUP（本机现状实测，`battery53` 25 行 / `battery54` 46 行，同前 25 行逐行对齐）**：
    `XREADGROUP GROUP nosuchg c STREAMS t53:ok 0-0` 与 `… >` 都回 `*-`
    （`battery53:18`、`:20`、`:21`，改后 `battery54` 同三行仍是 `*-`），读不存在的键也回 `*-`
    （两边都是 `:16`）。上游 :1505-1514 对这三种情形都回
    `-NOGROUP No such key '%s' or consumer group '%s' in XREADGROUP with GROUP option`
    并中止整条命令。同族里 `XPENDING` 已经在发 `-NOGROUP No such key … or consumer group …`
    （`battery53:22` / `battery54:22` 实测原文），所以这不是新文案，是把那一支的判据接过来。
    `xreadgroupHistory(...)` 把"键或组不在"返成 `null` 而不是空列表，就是给这一支留的口子。
    上一节在这里还写过一条"`XACK` 对不存在的组回 `:0`，上游那一句同样是 `-NOGROUP`"，
    行号记成 `battery53:26` —— **那一行不存在**（那一版 25 行），那句"上游同样是"我也没读过源码。
    读了 :1967-1980 的结论正相反：`No key or group? Nothing to ack`，键或组不在都回 `:0`，
    所以我们的 `:0` 本来就对（`battery54:23`、`:35`），这一条从清单里划掉，不是待改项。
  - **键空间看不见 stream（本机现状实测，`battery54:41-46`）**：`TYPE t53:ok` 回 `+none`、
    `EXISTS` 回 `:0`、`DBSIZE` 回 `:2`（那两枚是 `t53:l`/`t53:h`）、`DEL t53:ok` 回 `:0`
    而 `XLEN` 仍回 `:1` 且还能继续 `XADD`（"同一个键名两种视图"的另一半）。这一条要单独收口，
    而且它与下面那条键空间记账是同一个改动面（`MemoryStore` 得先认识 stream 这一型，
    `TYPE` / `EXISTS` / `DEL` / `DBSIZE` / `RENAME` 才谈得上把它们算进去）。
  - **答复层还没逐条量的（下面只写上游行号，本机现状待量）**：`XADD` 的 arity 用裸句
    `wrong number of arguments for XADD`（:1285，不是命令表形式的 `'xadd' command`），
    `MAXLEN` 非负用 `The MAXLEN argument must be >= 0.`（XADD :1269 / XTRIM :2492，
    我们是 `MAXLEN requires a non-negative integer`）；`XRANGE … COUNT 0` 回
    `shared.nullmultibulk` 即 `*-1`（:1380-1382，与"键不在回 `*0`"的 :1376 是两种形状）；
    `XINFO` 对不存在的键回 `shared.nokeyerr`（:2553，即 `-ERR no such key`），
    `XINFO CONSUMERS` 的组不在是 `-NOGROUP No such consumer group '%s' for key name '%s'`
    （:2562）；`XGROUP` 对不存在的键回那句
    `The XGROUP subcommand requires the key to exist. … MKSTREAM …`（:1841-1843），
    `MKSTREAM` 只在 argc 到位时才认（:1815-1818）；XREAD 的成对表不齐是
    `Unbalanced XREAD list of streams: …`（:1446）、缺 `GROUP` 是
    `Missing GROUP option for XREADGROUP`（:1484）、`GROUP` / `NOACK` 用错命令各有
    一句（:1455 / :1464）。**这一组我们没有一行本机实测**，动它们之前先 replay 一支
    `battery55` 把现状钉下来，别拿上游文案直接当"我们改完就对了"。
  - **`delivery_count` / `delivery_time`**：上游每次经 PEL 重交条目都会抬这两个值
    （:1111-1113），`XPENDING` 的逐条目形式与 `XCLAIM` 都读它。我们的 PEL 只有
    `Map<String, String>`（条目 → 消费者），这两个值没有读者，所以这一支不写；
    要把 `XPENDING` 的 IDLE / 次数形式或 `XCLAIM` 做对，得先把 PEL 换成带元数据的结构。
- `XREAD` / `XREADGROUP` 的 `BLOCK` 仍然是明确拒绝而不是实现（`XREAD BLOCK` 回
  `-ERR XREAD BLOCK is not supported…`）——拒绝是有意的：收下 `BLOCK` 等于对客户端谎称会阻塞。
- `XCLAIM` / `XSETID` 依旧没有实现（本机实测 `XCLAIM` 回 `-ERR unknown command 'XCLAIM'`）。
- `MemoryStore.keyVersions` 只增不减。
- `MemoryStore.keyTypeMaps` 仍只有 String 写路径维护；`existsDb` / `checkKeyType` 这些读它的
  方法对集合键一律"看不见"。`RENAME` 已经不读它了，但 `MemoryStore.rename()` / `renameDb()`
  还在读，且那两个方法主代码零调用方（集合分支只 `del(newKey)`，真接上会毁数据）。
- 位族这一版补到 `GETBIT / SETBIT / BITCOUNT / BITPOS / BITOP` 五支，**`BITFIELD` 仍然没有**：
  `CommandHandler.java` 里 grep `BITFIELD` 命中 0，而
  `_doc/001_arch/01-module-structure.md:62,213` 依旧把它列在展品清单里。同一份文档 `:64,223`
  宣传的 geo 一族八支（`GEOADD / GEOPOS / GEODIST / GEORADIUS / GEORADIUSBYMEMBER / GEOSEARCH /
  GEOHASH / GEOSEARCHSTORE`）也是零实现（各自 grep 命中 0）。这两处是文档在超前于代码，
  要么补实现、要么改文档，本轮没动。
- `maxauthtries` 只存在于注释里：`AUTH` 失败多少次都能重来，没有连接数上限那一档。
- `z-cache-server` 模块 0 条测试（`tally.py` 对它是硬 FATAL：`no surefire xml found`，
  不是"跑过了没测到东西"）。`Main` 只是 5 行转发，真正没人量的是 `core` 里那台
  `ZCacheServerMain` 的命令行面：`--password` / `--password-file`（33/39 行）确实实现了，
  但全仓库测试树 grep 它只有一处注释提到 —— 参数解析、鉴权开关、`HealthCheck`
  全部零回归，于是"默认值不够硬"那一条至今没有兜底。
- 测试端口仍是"先探一个空闲端口再 bind"，存在被抢占的窗口（频率见上）。彻底做法是
  `RedisServer` 支持 `port 0` 并回读实际端口，改动面覆盖两个测试类约 25 处，本轮没做。

## [1.3.5] - 2026-09-26

### Fixed

#### 类型闸门整个缺席：拿错类型的命令读一个键，会静默给出"不存在"
- `WRONGTYPE` 在任何路径上都没有被检查过。`SET k v` 之后 `HGET k f` 回 `$-1`——那不是"这个字段
  没有值"，那是键根本不是 hash，而客户端收到的形状和真的没有该字段时一模一样；`LPUSH k x`
  更是直接往另一棵存储里写，同一个键名下两种类型并存，`DBSIZE` 只数一个位置、`TYPE` 只报一种。
- 现在 `MemoryStore.typeOfDb(db, key)` 是唯一的尺（`EXISTS` / `TYPE` / 写侧闸门共用它），
  命令按自己的键位置逐位校验（`SINGLE` / `ALL` / `ALL_BUT_LAST` / `FIRST_TWO` / `FROM_SECOND`，
  表里 76 条命令，覆盖 String / Hash / List / Set / ZSet 五族），命中别的类型回 `-WRONGTYPE`。
- `SET` / `SETEX` / `PSETEX` 刻意不在这张表里：Redis 的 `dbOverwrite` 就是让它们盖掉旧值，
  旧类型由 `MemoryStore.putDb` 的 `clearOtherTypes` 负责清。
- `DEL` 同步修掉：它此前只清 `keyTypeMaps` 里记着的那一种，另一种留在原地。闸门让"两种类型并存"
  不再能被写出来，但 `DBSIZE` 归零这件事得由 `DEL` 自己保证——现在它逐棵存储清键，
  每个键名只计一次删除数。

#### EXEC 的"随机中止"：上一条事务的 WATCH 从来没被清掉
- `resetContext()` 的注释写着"保留 WATCH 信息"，Redis 的语义却是 **EXEC 与 DISCARD 都会 flush
  全部被观察键**。后果是这条连接之后每个 `EXEC` 都可能被一次毫不相干的写入打掉，而肇因来自
  一条早就结束的事务——看着像事务随机失败。

#### WATCH 的两把尺各量各的：该中止的中止不了，不该中止的反倒中止
- `watch()` 快照版本用调用方给的 provider，`exec()` 复查却调 `getCurrentVersion()`——它的旧实现
  `return 0L`，注释说"子类可覆盖"，而没有任何子类覆盖过。于是"WATCH 之后别人改了键"永远测不出
  来（照常提交），而"WATCH 之前键就写过版本非 0"反倒比出假不一致（无端中止）。方向整个是反的。
- provider 现在随 `watch()` 存进这条连接自己的 `TransactionManager`，WATCH 与 EXEC 共用同一把尺。
- 版本号的命名空间必须含库号：以前 `keyVersions` 只按键名记，`WATCH` 落在 3 号库、别人在 0 号库
  写同名键就能把这条事务打掉。现在 `getKeyVersion(db, key)` / `bumpKeyVersion(db, key)`，
  且 `WATCH` 把当时的库号快照下来（中途 `SELECT` 到别的库再用同名键比对是另一个库的写入）。
- `bumpWatchedKeys()` 补齐多键写命令（`RPOPLPUSH` / `LMOVE` / `SMOVE` 的目标键）与 `MSET`
  的逐键 bump，否则这些命令改了被 WATCH 的键而版本号不动。

#### XREADGROUP 永远投不出去的第二个条目
- `">"` 的判断只比较条目 ID 的**毫秒段**。同一毫秒内 `XADD` 两条（测试里 `1-1`、`1-2` 就是这么来的，
  生产中用 `*` 也常常撞上），第二条的毫秒段不"新于"游标，于是**永久**取不到，且没有任何报错——
  消费组看着像漏消息。现在游标记满两段（`lastDeliveredId` + `lastDeliveredSeq`），
  `isNewerThanLastDelivered()` 按完整 ID 比较；`XGROUP CREATE` 的起始 ID 同样按两段解析。

#### XPENDING 有三处对不上账
- 每个消费者的待确认数此前填的是 `consumer.getPendingCount()`，而那个自增计数器从来没有累加过：
  `XREADGROUP` 领了三条、一条没 ACK，`XPENDING` 仍报 0。现在从 `pendingEntries` 现算，
  账上为 0 的消费者也照常列出（Redis 的汇总就包含它们）。
- 汇总数从 `StreamStore.xpending()` 的 `Object[]` 里按 `(Long)` 取，而生产侧放的是 `Integer`
  ⇒ 走 socket 的 `XPENDING key group` 必回 `-ERR internal error: class java.lang.Integer cannot
  be cast to class java.lang.Long`。这条路径以前没有任何测试经过，所以一直没现形。
- 键或消费组不存在时回**空数组**：客户端把它读成"0 条待确认"，即"一切正常"。现在回
  `-NOGROUP No such key '...' or consumer group '...'`。
- 未实现的明细形态（`IDLE` / `start end count [consumer]`）明确报错，不再把多余参数丢掉、
  拿汇总冒充明细。

#### XINFO CONSUMERS 只存在于注释里
- `handleXinfo` 的 javadoc 写着支持 `CONSUMERS`，`switch` 里却没有这个 case，
  所以 `XINFO CONSUMERS key group` 永远回 `-ERR syntax error`。现在给出真实消费者行。

#### XADD / XTRIM 的 MAXLEN 参数形状不合 Redis
- 不认 `MAXLEN ~` / `MAXLEN =`（近似与精确两种前缀都是 Redis 的合法写法），`~` 时直接把
  `~` 当成数字解析而失败；缺 count 时报错形状也不对。现在两种前缀都收，非整数回
  `-ERR value is not an integer or out of range`，负数回 `-ERR MAXLEN requires a non-negative integer`。
- `XTRIM key MAXLEN 0` 此前什么都不清（`entries.size() - 0` 算出的删除数被当成 0），
  现在如实清空整条流。

#### SLOWLOG 从未接到真实服务器上
- `SlowLog` 的静态字段只有测试赋过值，真实服务器里恒为 `null`，`handle()` 末尾读它的那几行
  也就恒不执行 ⇒ `SLOWLOG GET` 永远回 `-ERR SlowLog not configured`，而直接 `new CommandHandler`
  的单测反倒全绿。`RedisServer.initPersistence()` 现在按 `-Dzcache.slowlog-log-slower-than`
  （默认 10ms）建一份并接上，`DEBUG SLOWLOG-RESET` 与 `SLOWLOG RESET` 走同一个对象。

#### INFO 报的端口和版本不是这台服务器
- `tcp_port` 写死 `6379`，而 `--port 0`（compose/测试里真实存在）时监听端口完全是另一个数；
  `z-cache_version` 写死 `1.0.2`，pom 早已是 1.3.x。现在端口取这条连接实际绑到的那个，
  版本与 `ZCacheServerMain` 共用 `CommandHandler.serverVersion()`（读 MANIFEST，读不到如实报 `dev`）。

#### CLIENT LIST 只列发起者自己，sub=/psub= 恒 0；CLIENT KILL 是假 +OK
- `CLIENT LIST` 以前只拼发起者那一行，等于"连接列表"里永远只有一个元素；`sub=0 psub=0` 是写死的
  字面量，而不是"没量到"——订阅中的连接被 pub/sub 闸门挡住根本执行不了 `CLIENT`，能从 socket
  看到这些字段的只有旁观者，所以那个 0 永远不会被任何人证伪。现在 LIST 遍历本机连接表，
  `sub=`/`psub=`/`age=`/`idle=`/`multi=`/`cmd=` 全部取真实状态，并补 `CLIENT INFO`。
- `CLIENT KILL` 以前"收下参数、回 `+OK`、什么都不做"：调用方据此认为对端已被踢掉，而对端好端端
  活着。现在支持 `ID <id>` / `LADDR ip:port` / `addr ip:port` / 客户端名四种形式，真的关闭目标
  连接，找不到才回 `-ERR No such client`。

#### 一台服务器的 pub/sub 与连接表会漏进另一台
- `pubSubManager` 与连接登记表都是 `CommandHandler` 上的**裸静态字段**，而且每 accept 一条连接，
  `RedisServerHandler` 的构造函数就覆写一次那个静态量。同一个 JVM 里两台服务器（跑整模块测试正是
  这个形态）会出现"A 的 SUBSCRIBE 记进一个管理器、B 的 CLIENT LIST 读另一个"，量出
  `sub=1 psub=0` 这种对不上账的数；跨实例的 `CLIENT KILL` 还能踢掉别人服务器的客户端。
- 注入探针实测过旧代码：在 B 上 `PUBLISH` 一个只有 A 订阅的频道，返回 **`:1`**（应为 `:0`）——
  不是显示问题，是一条服务器的订阅者真的会收到另一台服务器上发布消息的通路。
- 现在每台服务器持有自己的连接登记表与 MONITOR 集合，由 `RedisServer` 经
  `RedisServerHandler` 注入到该服务器的每条连接（`bindSharedComponents`），静态字段退化为
  "没人注入过"时的进程级默认值（嵌入式与单测走那条），路径行为不变。
- `channelContext` 补 `volatile`：`CLIENT LIST` 是旁观者线程直接读**别的连接**的 handler 字段，
  以前没有任何 happens-before，这正是"整模块连跑才复现、单跑全绿"的那种间歇来源。

### Added
- 新文件 `RedisServerProtocolSemanticsTest`：16 条走真实 Netty 监听 + RESP 往返的协议语义测试
  （慢日志接线、INFO 真端口/真版本、`BRPOPLPUSH`、单键单类型、六种类型的 WATCH 中止、
  库号隔离、`XTRIM`/`XADD` 的 MAXLEN 形状、`XPENDING` 真实计数、`XINFO CONSUMERS`、
  EXEC/DISCARD 清 WATCH、CLIENT LIST/KILL/INFO、未实现命令如实报错、跨实例隔离）。
  这一类缺陷单测看不见：直接 `new CommandHandler` 绕过的是真实连接装配，所以判据一律从 socket 拿。
- 全量 `mvn -B test`：96 + 336 + 133 + 2 = **567 例全绿**（五个模块）。

### 已知边界（本次没修，说清楚）
- **只有 RESP2**：README 此前第一行写着"RESP2/RESP3 兼容"，实测 `src/main` 里 `HELLO` 零处理
  （连 `case "HELLO"` 都没有），RESP3 的双推/`Map` 类型回复一概不存在。本轮把那句话改成 RESP2，
  但协议本身没有升级——需要 RESP3 的客户端请用 `HELLO` 失败的兜底路径（多数驱动默认走 RESP2）。
- **`EVAL` / `EVALSHA` / `SCRIPT` 从未实现**，而 README 的命令表里标的是 ✅：服务端没有 Lua
  解释器，`DistributedLock` 因此退化成"先 GET 校验再 DEL"的非原子写法。本轮只把表里那格改成
  🚧，没有顺手补解释器。
- **`EXPIRE` / `TTL` / `PERSIST` 只认 String 键**：`MemoryStore.expireDb` 从 `stringStores` 取键，
  实测（socket 层，本轮量出来的）对一个 `HSET` 出来的 hash 键：`EXPIRE k 100` 回 `:0`、
  `TTL k` 回 `:-2`、`PERSIST k` 回 `:0`，而同一个键 `TYPE` 回 `+hash`、`EXISTS` 回 `:1`。
  也就是说集合键**永远不会过期**，而 `TTL` 的 `-2`（"键不存在"）与 `EXISTS` 的 `1` 直接互相打脸。
  Redis 在这些命令上都回 `:1` 并真的挂上 TTL。修它需要一套逐类型的过期存储，不在本轮范围内；
  本轮把 `EXISTS` / `TYPE` / 类型闸门统一到 `typeOfDb` 之后，这个分歧从"看不出来"变成"量得出来"。
- `MemoryStore.keyTypeMaps` 仍是半接线状态（只有 String 写路径维护它）：`typeOfDb()` 已经不读它，
  但键空间遍历侧还有人在读，所以它没被删。
- `SlowLog` / `StreamStore` / AOF / RDB 仍是进程级静态：同一 JVM 里两台服务器共用一份。
  `StreamStore` 尤其明显——A 机 `FLUSHDB` 会把 B 机的 stream 一起清掉。（本条已由 1.3.6 修掉，
  当时缺的就是这一眼：两条实测判据写在 1.3.6 那一节里。）
- `MONITOR` 的可见范围已随连接表收到"每台服务器一份"，但 `monitorClients` 的默认值仍是进程级
  集合：未经 `RedisServer` 装配的连接（单测直接 new）会落进那个共享集合里。
- 未实现的命令一律如实报错而不是冒充：`XREAD`/`XREADGROUP` 没有 `BLOCK`；`XPENDING` 没有明细形态；
  `CLIENT NO-EVICT`、`DEBUG OBJECT` 明确拒绝。
- Stream 仍然完全不进持久化（沿 1.3.4 的边界）。
- `keyVersions` 只增不减：长跑进程里每个被写过的键都留一个条目，没有回收路径。
- `ConnectionResourceRegistry` 是死代码（主代码零调用方），它的 `getIdleTimeMs()` 复制粘贴错了、
  返回的是 maxmemory-policy 字符串。本轮没有接线也没有删，避免"顺手改动"混进这版。

## [1.3.4] - 2026-09-26

### Fixed

#### RDB 快照只装得下 DB 0
- `StoreAccessor` 的每个方法都没有库号，`writeRdbFile` 更是把 `dbCount` 写死成 1、`dbIndex`
  写死成 0：服务对外承诺 16 个库，快照里只放得下 DB 0，`SELECT 3` 之后写进去的数据
  在重启后静默消失（恢复侧还把所有键一律塞回 DB 0）。
- `getAllExpirationEntries()` 的直接返回 `new HashMap<>()`，注释写着"过期信息存储在
  ValueWrapper 中"——那份信息从来没被取出来过。于是 `SETEX k 3600 v` 落进快照后
  `expireAt` 恒为 -1，**重启后过期键变成永久键**。
- 现在 `StoreAccessor` 带 `getDbCount()` 与逐库参数，`MemoryStoreAccessor` 从
  `MemoryStore.stringStores[db]` 读真实 `expireAt`；`restoreString` 对"停机期间已经到期"的键
  直接丢弃，不再复活成永久键。集合类型键的 TTL 见文末"已知边界"。

#### SAVE / BGSAVE / LASTSAVE 三个命令一个字都没落盘
- 三条 case 此前是写死的回复：`SAVE` 和 `BGSAVE` 都回 `OK`（其中 `BGSAVE` 更早的版本还直接
  别名到 `handleSet`），`LASTSAVE` 回**当前时间**——三条命令同时给出"有快照、刚打过"的假象，
  而 `RdbPersistence` 压根没被接进来。
- 现在 `SAVE` 真调 `save()`、`BGSAVE` 在调度线程上异步打（已有后台快照在跑时如实拒绝，
  与 Redis 一致）、`LASTSAVE` 报最近一次**成功**的时刻、从未成功过为 0；
  未配 `--data-dir` 时前两条明确回 `-ERR`，不再装作成功。
- `save()` 内部两处"报了成功其实没落地"也一并修掉：`storeAccessor == null` 从
  "打条 WARNING 然后 return"改为抛异常；临时文件→目标文件的 `renameTo()` 返回值此前没人看，
  改 `Files.move(..., REPLACE_EXISTING)`，失败即抛。
- **定时快照此前从来不会发生**：`RdbPersistence.start()` 与 `onWrite()` 在主代码里都是零调用方，
  所以调度器没起过，就算起了 `writeCounter` 也恒为 0、`shouldSave()` 永远判 false。
  现在 `initPersistence()` 会 `setSaveStrategy` + `start`，并新增三项配置：
  `-Dzcache.save-seconds`（默认 300）、`-Dzcache.save-changes`（默认 1000，两者任一为 0 即关闭
  定时快照）、`-Dzcache.appendfsync`（`always`/`everysec`/`no`，默认 `everysec`，非法值不静默采纳）。

#### 快照落在 `--data-dir` 之外
- `initPersistence()` 只用路径去 `load(rdbPath)`，从不把路径交给写侧；`dbFilePath` 字段默认是
  相对路径 `"dump.rdb"`，于是 `SAVE` 与优雅停机快照全都写进**进程 cwd**，
  也就是那个没人会再去读回的地方。现在显式 `setDbFilePath(dataDir + "/dump.rdb")`。

#### AOF 只写不读：宣传的掉电恢复一次都没兑现过
- `loadAof()` 在主代码里零调用方——AOF 文件越长越勤快地写，然后没有任何人读过它。
  现在启动时按 Redis 的顺序恢复：**有 AOF 就只认 AOF，不再叠 RDB**（两份都读等于把快照里
  已经反映过的写命令再演一遍，`SET` 幂等看不出差别，`LPUSH` 会让列表原地翻倍）。
- 重放走 `CommandHandler.replayCommand()`，期间 `loading` 标志压住回写：否则每开一次机，
  日志就把自己的内容抄一遍。
- 补齐写命令表：`HINCRBYFLOAT`、`LMOVE`、`SPOP`、`SINTERSTORE`、`SUNIONSTORE`、`SDIFFSTORE`、
  `ZREMRANGEBYLEX/RANK/SCORE` 此前根本不在 `WRITE_COMMANDS` 里，连"落日志"这一步都没发生。
- 去掉 `MULTI` / `EXEC` / `DISCARD`：`EXEC` 会把队列里的命令逐条重新走一遍 `handle()`，
  每条各自落 AOF，再记一遍事务边界只会让重放多跑一次空事务。
- 当前连接不在 DB 0 时，AOF 里先补一条 `SELECT <db>`——否则重放用的是全新连接（db 恒为 0），
  5 号库的数据会全部落进 DB 0。
- `BLPOP` / `BRPOP` / `BRPOPLPUSH` 按非阻塞等价命令（`LPOP` / `RPOP` / `RPOPLPUSH`）记录，
  超时（什么都没弹出）时不记。不翻译的话"BLPOP 消费掉的那个值"在日志里毫无痕迹，
  重放后它会回到源列表里被消费第二次；直接记原命令则会在开机时真的阻塞在空列表上。
- `loadAof` 的解析从"逐行读、完全无视声明长度"改成按字节长度取：写侧记录的 `$<len>` 是
  UTF-8 字节数，而值里完全可以含 `\r\n`（`SET` 的合法取值），按行读会把这种值从第一个换行处
  切断、剩下的半截还会被当成下一条命令的开头。尾部截断（掉电时最后一条只写了一半）现在只丢
  那一条，前面的照常重放。
- `closeAof()` 改用 `shutdown()`：`stop()` 只关文件，两个调度线程池收不回，嵌入式起停一次
  就泄漏一对线程。

### Added
- `RedisServerLifecycleTest` 增加 5 条端到端回归：逐库快照 + TTL 活过重启 + 过期键不复活、
  无 dataDir 时 `SAVE`/`LASTSAVE` 如实报错、AOF 跨三代实例恢复（含库号、被 BLPOP 消费的值、
  含 CRLF 的值、重放不回写）、定时快照无需 `SAVE` 自己落盘、`appendfsync` 档位与非法值。
  全量 `mvn clean test`：96 + 320 + 133 + 2 = **551 例全绿**。

### 已知边界（本次没修，说清楚）
- **Stream（`X*`）不参与任何持久化**：既没有 RDB 段落，也不写 AOF —— `XADD` 在 `*` 形态下按
  当前时间生成条目 ID，重放会造出一批 ID 完全不同的条目，看着像存下来了其实对不上。
- 只有 String 键的 TTL 会进快照：`MemoryStore` 的过期信息只挂在 `stringStores` 上，
  集合类型键的 `EXPIRE` 本来就没有一个统一的地方读。
- 快照是"边读边写"的一致性级别：调度线程直接遍历活键空间，不做 fork，所以定时快照可能拍到
  一次写入的中途状态。`SAVE` 与优雅停机同样是这个级别（这比之前的"根本没有快照"仍是净收益）。

### 已经发布到 Central 的版本实测状态（2026-09-26，逐个坐标 curl）
| 版本 | repo1 | 外部消费者 |
|---|---|---|
| `1.0.2` | 200 | ❌ pom 的 parent 指向未发布的 `z-opc:1.0.0-SNAPSHOT`，且 `z-util-serialize-*:1.0.9` 不存在 |
| `1.3.0` | **404（整个版本从未发布成功）** | — |
| `1.3.1` | 200 | ❌ 同 `1.0.2` |
| `1.3.2` | **404（六模块 pom+jar 十二个坐标全 404）** | — |
| `1.3.3` | 200（含 `-sources` / `-javadoc`） | ✅ 第一个外部真正能解析的版本 |

所以 `1.3.2` 并不像本次提交中途以为的那样"已经传上去、坏版本永久留在 Central"——
它的 bundle 上传了，但没有任何坐标落到 repo1。不可撤销这件事对 `1.0.2` 与 `1.3.1` 成立，
对 `1.3.0` / `1.3.2` 不成立。

## [1.3.3] - 2026-09-26

### Fixed

#### 发布出去的构件外部消费者根本解析不动
- Central 上 `io.github.yuku123:z-cache:1.0.2` 与 `:1.3.1` 这两个聚合 pom 的 parent 写着
  monorepo 的 `com.zifang:z-opc:1.0.0-SNAPSHOT`——它从未（也不能）出现在 Central 上
  （`com/zifang/z-opc/` 实测 HTTP 404）。任何仓库外的项目第一次解析就得到
  `Could not find artifact com.zifang:z-opc:pom:1.0.0-SNAPSHOT (absent)` +
  `Failed to read artifact descriptor for io.github.yuku123:z-cache-core:jar:1.3.1`
  （这条是本次在空本地仓库里跑 consumer 探针实测到的输出）。
  本机 `mvn` 之所以从没报错，只是因为兄弟目录 `../pom.xml` 恰好就在磁盘上。
- `1.3.0` 是例外：它发布时聚合 pom 里没有 parent，`parent` 那条线本身是通的——
  但实测 repo1 上 `1.3.0` 整个版本 404（`z-cache/1.3.0/z-cache-1.3.0.pom` 与
  `z-cache-core/1.3.0/z-cache-core-1.3.0.jar` 都是），它压根没发布成功，所以也谈不上"能用"。
- `central` profile 下接入 `flatten-maven-plugin`（`flattenMode=oss`，`updatePomFile=true`）：
  发布用的 pom 去掉 parent、把继承来的依赖版本全部写成字面值，install/deploy 用它替换。
  不带 `-P central` 的本地 monorepo 构建链完全不变。

#### z-cache-core 依赖了一个 Central 上不存在的版本
- `z-util-serialize-core` / `z-util-serialize-schema` 在 `1.0.2` 与 `1.3.1` 的 pom 里都钉在
  `1.0.9`，而这个版本只存在于本机 `~/.m2`（由 monorepo 里的 z-util 现场 install 出来的），
  Central 实测只有 `1.0.10`（`.../1.0.9/z-util-serialize-core-1.0.9.jar` → HTTP 404）。
  即便修好了 parent，消费者下一步仍会卡在 `z-util-serialize-core:jar:1.0.9 (absent)`。
  抬到 `1.0.10`（实测该 jar 里 `CodecRegistry` / `ReflectCodec` / `ZSerializer` / `ZDeserializer`
  四个被实际 import 的类都在）。

## [1.3.2] - 2026-09-26

### Fixed

#### CommandHandler 写入落到了没人读的空库（数据静默丢失）
- `HINCRBYFLOAT`、`LMOVE`、`SPOP`、`ZLEXCOUNT`、`ZRANGEBYLEX`、`ZREVRANGEBYLEX`、
  `ZREMRANGEBYLEX`、`ZREMRANGEBYRANK`、`ZREMRANGEBYSCORE`、`ZRANDMEMBER` 等命令此前读写
  `CommandHandler` 的静态字段存储，而这条存储与 `MemoryStore` 的 per-DB 存储不是同一份数据：
  命令返回成功，但值并没有进入其它命令（以及 `KEYS`/`DBSIZE`/`EXISTS`/持久化）实际读取的键空间。
- 删除了这些静态字段、其懒初始化分支以及 `public static setXxxStore` 注入点；全部改为
  `store.getXxxStore(currentDb)`。

#### ZREMRANGEBYLEX 的 `[` 闭区间从未生效
- `SortedSetStore.compareLex` 只处理了开区间前缀 `(`，把 `[a` 当成字面量参与比较
  （`'a' > '['`），导致 Redis 里唯一的"含端点"写法恒不匹配。`ZRANGEBYLEX` /
  `ZREVRANGEBYLEX` / `ZLEXCOUNT` 三个命令因此长期返回空。

#### 阻塞命令的协议形状与服务隔离
- `BLPOP`/`BRPOP` 超时返回 `*0`（长度为 0 的数组），RESP2 语义应为 null 数组 `*-1`。
- 阻塞命令此前与业务命令共用同一个 Netty 线程组：少量 `BLPOP key 0` 即可占满线程，
  让所有普通流量停摆。现在阻塞命令调度到独立的线程组（`zcache.blocking-threads`，
  默认 `max(16, cores*4)`），业务线程组为 `zcache.business-threads`（默认 `max(4, cores*2)`），
  连接在阻塞期间关闭 `autoRead`，客户端断线时打断并回收 parked 线程。

#### INFO
- `keyspace` 段此前统计的不是各 DB 的真实 key 数，且只覆盖部分类型；现在按
  `store.getDbCount()` 逐库汇总 String/Hash/List/Set/SortedSet 的键数，只输出非空库。

### Added
- `RedisServerLifecycleTest`：针对真实监听端口 + 裸 RESP 的端到端回归，覆盖无 dataDir 启动、
  `connected_clients` 计数、NOAUTH 前置、跨线程 stop 不死锁、BLPOP 只弹请求的 key、
  BLPOP 超时不偷别的 key、阻塞命令不饿死普通流量、断线释放 worker 线程、
  以及集合类删除命令的效果必须出现在其它读取器看到的数据里。

## [1.3.1] - 2026-09-19

### Fixed
- Upgraded Netty from 4.1.100 to 4.1.138 to fix CVE-2023-44487 (HTTP/2 Rapid Reset)

## [1.3.0] - 2026-09-19

### Added

#### Distributed Lock (z-cache-client)
- `DistributedLock` interface with `tryLock` / `unlock` / `renew` / `currentOwner` / `close`
- `DistributedLockImpl`: SET NX PX acquisition, Lua EVAL atomic unlock, Watchdog auto-renew
- `Lock` handle POJO with ownerId, fencingToken, ttlMs
- `LockWatchdog`: background auto-renew at ttlMs/3 interval
- `ZCacheClient.distributedLock()` factory method
- unlock.lua + renew.lua scripts for future EVAL support
- 15 unit tests

#### Stream Consumer Groups (z-cache-core)
- `StreamEntry`, `Stream`, `ConsumerGroup`, `StreamStore` data structures
- 13 Stream commands: XADD, XLEN, XRANGE, XREVRANGE, XDEL, XTRIM, XREAD, XREADGROUP, XGROUP (CREATE/DESTROY/CREATECONSUMER/DELCONSUMER), XACK, XPENDING, XINFO
- Consumer group support with pending entries tracking
- 20 unit tests

#### Operations Commands (z-cache-core)
- CLIENT: LIST, GETNAME, SETNAME, ID, KILL, INFO, NO-EVICT
- DEBUG: SLEEP, OBJECT, SLOWLOG-RESET, ERROR (safe subset)
- MONITOR: toggle command monitoring with Redis-compatible output
- RESET: clear client state

#### Pub/Sub Pattern Matching (z-cache-core, existing)
- PSUBSCRIBE / PUNSUBSCRIBE with glob pattern support (*, ?)
- PUBSUB CHANNELS / NUMPAT / NUMSUB

#### Persistence (z-cache-core, existing)
- AOF: always / everysec / no strategies
- RDB: BGSAVE / SAVE snapshots

### Changed
- Version bump 1.1.0 → 1.3.0

### Documentation
- 分布式锁方案选型调研报告
- 集群模式架构选型调研报告
- 分布式锁设计文档
- 集群模式运维手册 (12 chapters)
- README 1.3.0 中文版
- 1.3.0 发布前合规审计报告

## [1.1.0] - 2026-08-15

### Added
- Pipeline support
- Transaction (MULTI/EXEC/DISCARD/WATCH)
- Pub/Sub (SUBSCRIBE/UNSUBSCRIBE/PUBLISH)
- AOF + RDB persistence
- SlowLog

## [1.0.2] - 2026-07-01

### Added
- Initial release with String/Hash/List/Set/SortedSet commands
- Netty-based RESP protocol
- Multi-database support (0-15)
- LRU eviction

---

**Full Changelog**: https://github.com/yuku123/z-cache/compare/v1.1.0...v1.3.0
