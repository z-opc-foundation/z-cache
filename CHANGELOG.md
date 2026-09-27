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
- ~~**反方向这一版没做，而且是量出来的**：stream 键对键空间**仍然不可见**。~~
  —— **本轮已闭**（见下面《stream 键是键：TYPE / EXISTS / DBSIZE / KEYS / SCAN / RANDOMKEY /
  RENAME / MOVE / FLUSHDB 共用一把尺》那一节，`battery68` 83 行两侧对拍，实测翻 30 行）。
  当时那一份读留在这里，是为了记改前的形状从哪量来的：
  `MemoryStore.DataType` 只有 `{NONE, STRING, HASH, LIST, SET, ZSET}`，`typeOfDb` 从不问
  `StreamStore`，`streams()` 只被 stream 自己的 handler 引用 —— 于是 `battery54:41`
  `TYPE t53:ok` 回 `+none`、`:42` `EXISTS` 回 `:0`、`:43` `DBSIZE` 回 `:2`
  （那两枚是 `t53:l` 与 `t53:h`，有条目又有组的 `t53:ok` 一分不占）、`:44` `DEL t53:ok` 回 `:0`
  而 `:45` `XLEN` 仍回 `:1`、`:46` 还能继续 `XADD`。`TYPE` 报 `+none` 而 `XLEN` 报有条目，
  正是"同一个键名两种视图"的另一半。这一半要改的是 `MemoryStore` 的键空间而不是
  `CommandHandler` 里加一句话，所以单独一票做 —— 本轮就是那一票，做的方式正是"让
  `typeOfDb` 去问第六张表"，然后逐个读者复核。`streamTypeConflict` 的文档注释里写明的
  前提（它问的是"这枚键名被别的类型占着吗"，不是"这是不是 stream"）仍然成立，
  闭的是另一头。

#### 错误码不是文本：`-ERR NOGROUP …` 对按码分支的客户端等于"未知错误"

- **形状问题不等于文案问题**：上游以 `-` 开头的错误串是**自成一码**的 ——
  `-BUSYGROUP`（t_stream.c :1888-1889 直接 `addReplySds`）、`-NOGROUP`（:2562 同上）。
  我们此前把它们塞进 `-ERR` 的文本里（`-ERR BUSYGROUP Consumer Group name already exists`、
  `-ERR NOGROUP No such consumer group …`），客户端 `switch(reply.getError().split(" ")[0])`
  这一路全部落到 default。这一支只改**码**与**那一问**，不碰其余答复文案。
- **五处**（`CommandHandler.java`）：XGROUP 重名回 `-BUSYGROUP Consumer Group name already exists`
  （:2812）；XINFO 对不存在的键回 `-ERR no such key`（:2922，:2553 `shared.nokeyerr`，
  三个子命令共用这一问而不是各 case 一份）；`XINFO CONSUMERS` 的组不在回
  `-NOGROUP No such consumer group '%s' for key name '%s'`（:2953，:2562）；
  `XINFO STREAM`（少一个参数）回 `-ERR syntax error, try 'XINFO HELP'`（:2916，:2542-2543）；
  以及下面单独一节的 XREADGROUP NOGROUP（:2744）。
- **`XREADGROUP` 的"键或组不在"此前答成"暂时没有新消息"**：改前三条形状全回 `*-`（空数组），
  客户端把"组根本没建过"读成"没有新条目"，于是轮询永远不会停。上游 :1505-1514 对这三种情形
  都回 `-NOGROUP No such key '%s' or consumer group '%s' in XREADGROUP with GROUP option`
  并 `goto cleanup` ⇒ **整条命令作废**，后面那些合法位置一个都不交。
  判序也是判据的一部分：这一问排在类型闸（:1500）**之后**、`$`（:1518）与 `>`（:1535）
  两个特例位**之前**，所以"组不存在 + `$`"错成 NOGROUP 而不是那句"`$` 无意义"。
  `xreadgroupHistory(...)` 把"键或组不在"返成 `null` 而不是空列表，这一支就是把那个 `null`
  接到命令层（`StreamStore` 的注释同步改成"只覆盖并发移除窗口"）。
- **改前改后各 46/15 行实测**（同一支 battery、同一个 `zreplay.py`、同一条 `mvn -o -DskipTests package`）：
  `battery55` 46 行翻 **5** 行 —— `:8 :9`（`XINFO STREAM/GROUPS t55:nokey` `*-` → `-ERR no such key`）、
  `:10`（`-ERR NOGROUP …` → `-NOGROUP …`）、`:11`（`XINFO CONSUMERS t55:nokey g55` → `-ERR no such key`，
  键那一问排在组之前）、`:17`（`-ERR BUSYGROUP …` → `-BUSYGROUP …`）；
  第 6 处差异是 `:32` 的 `XADD … *` 自动 ID 毫秒值，**时钟噪声，不记账**。
  `battery56` 15 行翻 **6** 行（`:4-:9`，全在 NOGROUP 那一面），其中两行是"改前只答一半"的形状：
  `XREADGROUP GROUP g56 c STREAMS t56:ok t56:nokey 0-0 0-0` 改前交
  `*[*[$"t56:ok";*[]]]`（坏键被静默丢掉、好键照样答），改后两序都只回一个 `-NOGROUP`。
  **作废不留副作用**由 `:13` 说话：五条作废的 XREADGROUP 之后 `XPENDING t56:ok g56` 仍只有
  `c`（0 条）与 `c2`（1 条），条目是在 `:12` 那一次 `>` 才投出去的；`:14` `XACK t56:ok nosuchg 1-1`
  仍 `:0`（上游 :1975-1977 `No key or group? Nothing to ack`，不是待改项）。
- **"改前"这一半怎么量的，值得记下来**：第一版想用 `git archive HEAD | tar -x` 到 scratch 目录
  建改前 jar —— 那条 `mvn -o -DskipTests package` 静默把 **09-19 的 `z-cache-common` 旧字节**
  打进了 fat jar（`unzip -l` 里 `RespFrameReader` 命中 0，只有 6 个 protocol 类），服务器起来就
  `ClassNotFoundException`、replay 报 `NO PONG`。这次它**崩在门口所以没骗到人**；要是那批旧字节
  恰好能跑，我就会拿一个混合版本的 jar 当"改前"量。结论：**跨树建"改前构件"一律回主树做**
  —— 三个未提交文件先按字节搬到 `~/.cache/zcache_gauges/pre_fix_src/`（md5 `8ecc6567…` /
  `26b34b90…` / `ac397d2f…`），`git show HEAD:` 写回那三个路径（当场 `git diff --stat` 为空 =
  树等于 HEAD），建出的 jar `0f4e6474…` 里 `RespFrameReader` 命中 2；量完把备份 `cp` 回来并
  三个 md5 逐个对账，改后 jar `eeb88c1b…` 按字节放回 `target`（与留档副本逐字节同）。
- 回归落在真实服务器上：`streamErrorCodesTravelAsTheirOwnCode` 一条方法钉住 BUSYGROUP 的码、
  三种 NOGROUP 形状（组不在 / 键不在 / 多键两序整条作废）、"`0-0` 作废不消费"、
  断线重连后 `XREAD` 仍读出那条 1-1、`XREAD` 对不存在的键回 `*-1` 的对照，以及判序那一对
  （组不存在 + `$` → NOGROUP；组存在 + `$` → 仍是那句"`$` 无意义"）；
  `xinfoConsumersRepliesWithConsumerRows` 的三条期望串改成 `-ERR no such key` /
  `-NOGROUP No such consumer group …` 原文。
- **12 支具名变异，12 支全部点名判红**（`~/.cache/zcache_gauges/code_mut.py`，快照
  `code_snapshot/`，还原只从本次快照 `cp` + md5 对账，互斥锁 `mut.lock` 带 pid）。
  P1/P2 把码退成 `-ERR` 前缀，P3 摘掉那一问，P4/P5 各挪一处判序，P6/P7 只问键或只问组，
  P8 把组名换成字面量，P9 改成"不作废、只跳过那一键"，P10/P11/P12 动 XINFO 的三句。
  **P5 第一遍判的是 SURVIVED，而缺陷在尺上不在代码上**：这一支挪的是"类型 vs NOGROUP"的判序，
  唯一读这条判序的断言在 `streamFamilyHoldsTheSameOneTypeInvariant`（:1322，
  `sem:ty:string` + `$` 必须 WRONGTYPE），而探针选取器当时只写了 `streamErrorCodes…+xinfoConsumers…`
  两条 —— 它们都不碰"键被别的类型占着"的形状，于是"没人跑覆盖它的测试"被我记成了"变异等价"。
  选取器补上那一支之后**先跑阳性对照**（干净树上同一选取器 `Tests run: 2, Failures: 0`，
  `code_P5_control.log`），再打 P5 才红，红消息就是那一条：
  `键被别的类型占着时必须挡下 … expected: <-WRONGTYPE …> but was: <-NOGROUP No such key 'sem:ty:string' …>`。
  **改尺之前那一遍确实全绿，所以"改前 SURVIVED"不是一条缺陷记录，别照抄过去。**

#### XRANGE 的 `COUNT` 有**三种**答复形状，我们只有两种、而且拿的是 int 尺

- **上游那一段是三次"先答什么"**（`t_stream.c` `xrangeGenericCommand` :1348-1386）：
  `long long count = -1`（:1352）→ 两端 ID 先解析（:1356-1357）→ **再**逐位扫剩余参数
  （:1360-1373）→ 之后才 `lookupKeyReadOrReply` + `checkType`（:1376-1377）→ 最后
  `count == 0` 单独一答（:1380-1382）。扫描那一圈只认 `COUNT <值>` 这一对：
  `additional >= 1` 不成立（裸 `COUNT` 后面没值，:1363）或任何多余字（:1369）都回
  `shared.syntaxerr`；值按 `getLongLongFromObjectOrReply`（:1364）取，**负数就地钳成 0**
  （:1366）⇒ `COUNT -1` 与 `COUNT 0` 同答，而不是"不限"；重复 `COUNT` 是就地覆盖，后写的赢。
  答复形状因此分出**三种**：键不在 = :1376 的 `emptymultibulk`（`*0`）、`COUNT 0` = :1381 的
  `nullmultibulk`（`*-1`）、其余才是那张表。我们改前只有最后一种。
- **改前逐行实测**（`battery57.pre4`，与改后同方法同量具）：`:6`（`COUNT 0`）交整表、
  `:10`（`COUNT -1`）交整表、`:12`（裸 `COUNT`）与 `:15`（`EXTRA`）一律静默忽略、
  `:14`（`COUNT 1 COUNT 2`）是**先写的赢**（只回一条）、`:16`（`XREVRANGE … COUNT 0`）交整表、
  `:24 :25 :26`（`COUNT 4294967296 / 4294967297 / 2147483648`）回
  `-ERR value is not an integer or out of range`。取值那一头走的是 `intArg`：一是**int 尺**
  （上游 long long），二是超界抛 `NumberFormatException` 由分发层统一答成那句 not-an-integer
  —— 于是"你给了个很大的 COUNT"被报成"你给了个不是整数的 COUNT"。
  27 行里翻 **9** 行（6 语义 + 3 尺），两侧各 `wrote=27 lost=none`（`count_replay.log`），
  且改前/改后各重放一遍逐字节相同（`pre3==pre4`、`post3==post4`）。
- **不动的那些行才是这一支的对照组**：`:5` 整表、`:7 :8 :9` 正数截断、`:11 :18 :21` 坏值那一句、
  `:19 :20` 键不在仍是 `*0`、`:22` 坏 ID 仍抢在 `COUNT 0` 之前说话、`:27` `XLEN` 仍 `:3`。
  特别地 `:23`（`COUNT 18446744073709551616`，2^64）**改前后同形都拒** —— 换尺是换成 long long，
  不是换成"没有上界"，这一行就是为了不被顺手改成 `*0` 而留在电池里。
- **窄化那一个是两个陷阱叠在一起**：`4294967296` 直接 `(int)` 得 **0**，而 0 在下面那层
  （`StreamStore.xrange` 的 limit）语义是"不限"，于是"钳位"与"不限"共用一个值；
  `4294967297` 窄化成 **1**，三条被截成一条。所以钳位必须在窄化**之前**
  （`count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count`），两支各钉一行断言
  （`RedisServerProtocolSemanticsTest.java:2885`、`:2889`）。
- **判序**：`bad-id + COUNT 0` → 那句 invalid stream ID（`battery57:22`，改前后同形），
  而 `- + COUNT 0 bad-id` → `-ERR syntax error`（扫描那一圈排在这只 nil 之前）。
  **后一形状只在回归用例里钉着（:2898-2899），电池里没有这一行** —— 别以为翻电池能看见它。
  同一节里 `:2856`（键不在 + `COUNT 0` → `*0`）与 `:2861`（同位换成被 String 占着的键 →
  `-WRONGTYPE`）钉住"取键那一问排在 `count == 0` 之前"，这与上一节 XREADGROUP 的判序是同一类判据。
- **`XREAD` 的 `COUNT 0` 是反的，别顺手"修"成一致**：上游 XREAD 把 `count` 直接交给
  `streamReplyWithRange`（:1617 传的是那个 long long 本身，:1441 负数折 0，:1063 的 0 不截断），
  所以 XREAD 侧 `COUNT 0` = **不限**、XRANGE 侧 `COUNT 0` = **nil 数组**。这一条已有断言在前
  （`:639`），本轮只补了 XRANGE 那一半并在两处注释里互相点名。
- **回归**：`xrangeCountHasThreeShapesAndItsOwnPlaceInTheQueue`
  （`RedisServerProtocolSemanticsTest.java:2824-2910`）—— 三条阳性对照（无 COUNT / `COUNT 2` /
  `COUNT 9`）、三种形状各一行、判序三行、值的文法五行（`abc`、`+2`、`9999999999`、2^32、2^32+1）、
  收尾 `XLEN` 仍 `:3` 且 `GET sem:cnt:str` 仍 `hello`（闸门不改写字节）。
  全量 `mvn -o -B clean test`：**358 + 380 + 134 + 2 = 874，failures/errors/skipped 全 0**
  （`count_full.log`，core 一档从 379 抬到 380）。
- **13 支具名变异，13 支全部点名判红**（`code_mut.py Q`，Q1-Q13；打之前先在干净树上跑阳性对照，
  `count_control.log` 里 `Tests run: 1, Failures: 0`）。Q1 摘 nil、Q2 nil 退成空表、
  Q3 调换两问判序、Q4 负数不钳、Q5 摘掉 `additional >= 1`、Q6 多余字退成忽略、
  Q7 后写赢改成先写赢、Q8 long 尺退成 int 尺、Q9 类型闸挪到两问之后、Q10 摘掉起点 ID 那一问、
  Q11 `equalsIgnoreCase` 退成 `equals`、Q12 钳位挪到窄化之后。
  **Q13 是我按"等价变异"写的假设，实测把它证伪了**：我以为单独摘掉"键不在"那一问不可观测
  （store 对不存在的键本来就交空表），只删那一行、保留 nil 那一行跑一遍 ⇒ **KILLED**，
  红的是 `:2857` 那一条 `expected: <[]> but was: <*-1>`。也就是说这一问**独立可观测**
  （摘掉之后"不存在的键 + `COUNT 0`"会答成 nil），我原本打算写进这一节的"等价变异"那句不成立、
  已删。这一支留在尺上记的是"我以为等价，尺说不是"。
  另外 Q5/Q10 两支的红消息不是断言文案而是**兜底 catch 的 JVM 文本**
  （`-ERR internal error: Index 5 out of bounds for length 5`、
  `internal error: Cannot load from long array because "startId" is null`，见 `CommandHandler.java:481-483`）
  —— 这两道闸同时是"不漏 JVM 文本"那一族的闸，与前面 stream ID 那一节同源。

#### XADD / XTRIM 的那一圈扫描：`XADD` 有**两种** arity 句，而 `XTRIM` 的取键排在选项**之前**

- **先说哪一形 arity 句**（`src/server.c :314`、`:327`，从官方 `redis-5.0.14.tar.gz` 里读的，
  md5 `3b67522ee336aec9c6e26441a30194e0` 那份 `t_stream.c` 同一棵树）：命令表写的是
  `{"xadd",xaddCommand,-5,…}` 与 `{"xtrim",xtrimCommand,-2,…}`。`-5` 意味着
  `XADD key ID f v` 是**最短的能进函数的一形**，四个字（`XADD k 5-5 a`）在进函数之前就被命令表
  拦下，答的是表那一形 `-ERR wrong number of arguments for 'xadd' command`；进得了函数之后，
  `t_stream.c :1284-1286` 那句 `addReplyError(c,"wrong number of arguments for XADD")` 是
  **裸句、大写、没有 `'…' command`**。`xtrim` 的 `-2` 反过来：`XTRIM k` 进得来，它的下场是
  :2507-2510 那句 `XTRIM called without an option to trim the stream`，只有光杆 `XTRIM` 吃表那一形。
  我们改前把这两种形状混成了一个（`battery58` 的 `:9 :32 :35 :38 :39 :42` 六行都回
  `for 'xadd' command` / `for 'xtrim' command`），而且自造了一句 `XADD needs at least one field value
  pair` 顶在裸句的位置上。
- **XADD 的那一圈**（:1248-1280）决定三件事，都不是按位置写死的：`*` 是快路径直接 break
  （:1251-1254）；**`MAXLEN` 只在后面还有字时才算选项**（:1255 的 `&& moreargs`），否则它自己
  落进 :1274-1276 那一支被当成 ID 去 strict 解析 —— 于是 `XADD k MAXLEN bad-id 1` 答的是
  invalid stream ID 而不是别的；`~` / `=` 只在 `moreargs >= 2` 才被吃掉那一格（:1259-1264），
  所以 `XTRIM k MAXLEN ~` 的 `~` 在字数耗尽时**不被吃**，直接拿去 `getLongLongFromObjectOrReply`
  （:1265/:2488）而回那句 not-an-integer（`battery58:40` 改前后正是反的）。圈结束后才是
  `field_pos = i+1`（:1281）→ arity（:1284）→ **0-0 那一问（:1292-1295）**→ 取键
  （:1300 的 `streamTypeLookupWriteOrCreate`，WRONGTYPE 在 :1135）。
  **所以 0-0 排在 arity 之后**：`XADD k 0-0 a 1 b 2 3` 字段数是 3（奇数），:1284 先答，
  而 `XADD k 0-0 a 1` 才轮到那句 must be greater than 0-0。这两行各钉一条断言（`:3000`、`:3003`）。
- **XTRIM 是反的，别顺手统一**：`lookupKeyWriteOrReply(c, argv[1], shared.czero)` + `checkType`
  排在选项解析**之前**（:2461-2462）。于是"键不在"根本问不着值 ——
  `XTRIM <不在> MAXLEN abc`、`XTRIM <不在> FOO`、`XTRIM <不在> MAXLEN -1` 一律 `:0`
  （那是 :2461 的第三个实参 `shared.czero`，语义是"删了 0 条"）；被 String 占着的键
  `XTRIM <str> FOO` 一律 WRONGTYPE。改前这五行（`battery58:33 :34 :35 :38` 与 `:31` 的光杆）
  全都先吃了参数那一问。同一圈的 `MAXLEN` 那一支与 XADD 逐字同形（:2477-2496，同一句
  `The MAXLEN argument must be >= 0.` 在 :2491-2494），认不得的字一律 `shared.syntaxerr`
  （:2498）—— 我们改前在这里回的是自造的 `unsupported XTRIM strategy. Use MAXLEN`。
- **改前逐行实测**（`battery58.pre` 对 `battery58.post`，46 行）翻 **16** 行：
  `:9 :10 :11 :12 :13`（arity 两形与那句自造裸句、`bad-id a 1 b` 的判序）、`:16 :19`
  （MAXLEN 负数那句原文，XADD 两侧）、`:32`（`XTRIM k` 退成 arity 句）、`:33 :34 :35`
  （键不在该 `:0` 而各自答了值错/arity）、`:38`（`XTRIM <str> FOO` 该 WRONGTYPE）、
  `:39 :40 :41 :42`（`MAXLEN` 裸尾该 syntax error、`MAXLEN ~` 该 not-an-integer、
  负数该原文、`FOO` 该 syntax error）。两侧各 `wrote=46 lost=none`（`battery58_replay.log`）。
  **没动的 30 行是这一支的对照组**：`:1-:8` 的正常写入与 `MAXLEN = 2` 真裁一刀（`:27` 仍 `:2`）、
  `:14 :15 :17 :18`（0-0 那一句、`MAXLEN 5` 的正常收）、`:28 :29`（`MAXLEN 0`，见下面那条已知边界）、
  `:36 :37`（键不在 + `~ 1`、String 键 + 合法选项）、`:43`（`MAXLEN 2 5` 的多余字）、
  `:44 :45 :46`（真裁一刀 `:1`、`XLEN :2`、`GET` 仍 `hello` —— 闸门不改写字节）。
- **收口时我先错过一次**：多 field 条目的答复形状我按"每对再套一层"写了期望，当场红。
  读上游 `:985-1009` 才确认 `addReplyMultiBulkLen(c, numfields*2)`（:1000）交的是**平铺的 2n 个数**，
  红的是断言不是代码，我们的字节本来是对的那一方（`RedisServerProtocolSemanticsTest.java:3042-3045`
  把这段记在注释里）。
- **回归**：`xaddAndXtrimOptionCircleAnswersInUpstreamOrder`
  （`RedisServerProtocolSemanticsTest.java:2935-3052`）—— 两组阳性对照（多 field 收、`MAXLEN = 2` 真裁到
  `XLEN :2`）、arity 两形四行、MAXLEN 值三行（负数原文 ×2、`abc`）、"被拒的 XADD 不建键"
  （:2989-2992 的 `XLEN sem:xcnew` → `:0`，就是上游 :1289-1291 那段注释要防的事）、
  XADD 判序三行、XTRIM 取键四行、XTRIM 三种"认不得"五行、收尾真裁一刀加 `GET`。
  全量 `mvn -o -B clean test`：**358 + 381 + 134 + 2 = 875，failures/errors/skipped 全 0**
  （`b58_full2.log`，core 一档从 380 抬到 381）。
  **第一遍是红的，而且红的不是新用例**（`b58_full.log`：`Tests run: 381, Failures: 2`）——
  两处红都在 1.3.5 那轮《XADD / XTRIM 的 MAXLEN 参数形状不合 Redis》写的断言上
  （下面两处行号取自 `b58_full.log` 的栈，属重钉前那份文件）：
  `xtrimTakesTheRedisArgumentShape:322` 钉的是 `XTRIM k MAXLEN` 回 arity 句（上游回 syntax err），
  `errorRepliesCannotForgeAnExtraLine:1680` 钉的是 `XTRIM <从未创建的键> <怪 token>` 回错误
  （上游取键在先，那一格是 `:0`）。**旧断言把旧行为钉成了"不许改回去"**，所以这一支必须连它
  一起重钉（见上面那条已知边界里的重钉记录），不能靠删断言变绿：四条重钉后各自仍断"要么错要么原文"，
  而且给那个报错载体补了一条阳性对照（先 `XADD err:trim 1-1 a 1` 必须回 `1-1` ——
  判"报错形状不能劈帧"的那一格不能落在一个根本不存在的键上，否则它量的不是同一件事）。
- **13 支具名变异，13 支全部点名判红**（`code_mut.py R`，R1-R13；打之前先在干净树上跑阳性对照，
  `b58_test1.log` 里 `Tests run: 1, Failures: 0`，快照 `7f335e12df7d6cab175c0e38cee40505` 逐支还原对账）。
  R1 入口 arity 松到四个字、R2 XTRIM 入口摘到 `< 1`、R3 裸句退成命令表那一形、
  R4 XTRIM 的 `moreargs` 闸摘掉、R5 XADD 不吃 `~`/`=`、R6/R7 两处的 MAXLEN 负数句各退成自造句、
  R8 0-0 挪到 arity 之前、R9 字段数奇偶不查、R10 无选项退成 arity 句、R11 认不得的字退成自造句、
  R12 取键两问整块挪到选项之后、R13 类型闸与键不在互换。
  三条要说清楚：
  - **R2/R4 的红消息是兜底 catch 的 JVM 文本**（`-ERR internal error: Index …out of bounds…`），
    与上一节 Q5/Q10 同源 —— 这两道入口闸同时是"不漏 JVM 文本"那一族的闸。
  - **R5 红的位置与我预期的不同**：我以为摘掉 `~`/`=` 那一行会让 `XADD k MAXLEN = 2 6-6 d 4`
    把 `=` 当 ID 解析而回 invalid stream ID，实测回的是 `-ERR value is not an integer or out of
    range`（`=` 落进了 `MAXLEN` 的**值**那一格）。两支都该红，但 CHANGELOG 只按实测的这句写。
  - **R13 证明"取键那一问"内部也有可观测的先后**：被 String 占着的键在 store 那一层
    `getStream` 同样返回 null，所以"键不在"与"类型不对"换一下顺序就会把 WRONGTYPE 答成 `:0`
    —— 这一对不是那种"结构上等价的变异"，是独立可观测的（`battery58:38` 也翻这一行）。

#### XGROUP 的三道闸：MKSTREAM 那一格、"键必须存在"排在分派之前，而 `DELCONSUMER` 答的是条数

依据是上游 `xgroupCommand`（`t_stream.c:1798-1926`，本机 `redis-5.0.14/src/` 那份，md5
`3b67522e…`）与 `addReplySubcommandSyntaxError`（`networking.c:604-630`）。**流族没有参照实例**
（4.0.9 根本不认 stream），所以这一支的真值只有源码行号，下面每条都带着它。

- **上游的形状是"先按参数个数问三道闸，再按子命令分派"**，而我们是"先认子命令再数参数"——
  顺序本身就是行为，改前三格各自答各的：
  - `XGROUP CREATE <不在的键> g 0-0` 回 `+OK`（顺手把键建了出来）；上游 :1837-1845 要求键必须存在，
    那句 `The XGROUP subcommand requires the key to exist. Note that for CREATE you may want to use
    the MKSTREAM option to create an empty stream automatically.` 是三段字符串拼出来的原文。
    `DESTROY`/`DELCONSUMER`/`SETID`/`CREATECONSUMER` 的"键不在"同一句（闸门共用），改前分别回
    `:0`、`:0`、syntax err、`:0`。
  - `XGROUP CREATE k g 0-0 EXTRA` 回 `+OK`；上游 :1817-1824 里六个字且是 CREATE 时，
    第六个字**必须**是 MKSTREAM，否则那句"认不得的子命令"，而且这一问排在取键之前。
  - `XGROUP CREATE k g` / `XGROUP CREATE k` 回自造的 `wrong number of arguments for 'xgroup create'
    command`：命令表 `xgroup` 的 arity 是 `-2`（`server.c:320`），上游根本没有这一句，
    分派处 CREATE 只认 `argc==5 || argc==6`（:1860）、DESTROY 只认 4（:1902）、
    DELCONSUMER 只认 5（:1913），个数不对一律落回 `Unknown subcommand or wrong number of arguments
    for '<原样那一格>'. Try XGROUP HELP.`（第一个占位是 **argv[1] 照原样**，只有命令名大写）。
  - `XGROUP FOO k g` 回 `-ERR syntax error`，`XGROUP DELCONSUMER k <不在的组> c` 与
    `XGROUP SETID k <不在的组> 0-0` 也回 `-ERR syntax error`；上游后者是
    `addReplyErrorFormat("-NOGROUP No such consumer group '%s' for key name '%s'")`（:1848-1856），
    码就是 NOGROUP 本身。
- **`DELCONSUMER` 的答复值取错过**：上游 :1916-1917 交的是 `streamDelConsumer` 的返回值
  （:1765-1788，"这个消费者手上还压着几条"，消费者不在才 0），我们 `ConsumerGroup.destroyConsumer`
  返回 boolean、命令层翻成 1/0。实测 `battery59:37`：c7 名下压着两条没 ACK，我们回 `:1`、
  上游回 `:2`。改成返回**被清掉的条数**，且条数从 `pendingEntries` 现数而不是信 `Consumer.pendingCount`
  那个自增计数器（与 `perConsumerPending()` 同一个理由）。
- **实测翻行**：`battery59.txt` 39 行（`:1 :2` PING 与建 String、`:3-:6` 建流建组、
  `:38` 重复 DELCONSUMER 的 `:0`、`:39` 收尾 `GET`）——改前 `battery59.pre` 由
  `jar_xaddtrim`（= HEAD `7960050` 那棵树，class `51b20f9a…`）量得，改后 `battery59.post`
  由 `jar_xgroup`（class `0828479e…`）量得，两侧各 `wrote=39 lost=none`（`b59_replay.log`）。
  翻 **14** 行：`:7`（键不在该那句原文，改前 `+OK`）、`:14 :15 :16`（三种 CREATE 的字数形，
  改前各回自造 arity / `+OK` / 自造 arity）、`:17`（`FOO` 该那句"认不得"，改前 syntax err）、
  `:18`（`HELP`，见下面那条"仍差一层"）、`:20`（`DESTROY <不在的键>` 该那句原文，改前 `:0`）、
  `:24 :25`（组不在的 NOGROUP、键不在的那句原文）、`:27 :28 :29`（SETID 三格）、
  `:31`（`CREATECONSUMER <不在的键>`）、`:37`（DELCONSUMER 的 `:2`）。
  **对照组 25 行一条都没动**：`:4 :5`（建组与 BUSYGROUP）、`:8`（MKSTREAM 该收）、
  `:9`（`bad-id` + MKSTREAM 仍回 invalid ID，钉住":1869 排在 :1873-1879 建流之前"）、
  `:10 :11 :12`（三枚 XLEN 全 `:0` —— 这就是下面那条键空间盲的判据缺口）、
  `:13`（String 键仍 WRONGTYPE）、`:19`（光杆 `XGROUP` 仍吃命令表那句）、`:21 :22`（DESTROY 的 `:1`/`:0`）、
  `:23`（String 键的 DESTROY 仍 WRONGTYPE）、`:26`（组在而消费者不在仍 `:0`）、
  `:30`（`CREATECONSUMER` 在闸门之后照旧回 `:1`）、`:32-:36 :38 :39`。
- **收口时我先错过一次，这次红的是我自己的期望**：写完断言先按"`:2`"跑，第一次真红在
  `:2` vs `:1` —— 因为我在 `XREADGROUP` 发了两条之后先 `XACK` 掉一条，那一刻"还压着几条"就是 1，
  上游同样回 1。**这次是代码对、断言错**，修法是补一格而不是改代码：现在两格并钉
  （不发 ACK 的那组回 `:2`，ACK 掉一条的那组回 `:1`），前者打得住"布尔答复"，
  后者打得住"把发过的条数当剩余条数"。
- **回归**：`xgroupGatesRunBeforeSubcommandDispatch`（`RedisServerProtocolSemanticsTest.java:3054-3208`）。
  两组大小写阳性对照（`XGROUP create …` 与 `… 0-0 mkstream`，都落 `:1818`/`:1860` 的 strcasecmp）、
  MKSTREAM 三格（白给的、顶闸的、`bad-id` 抢先的）、"键不在"四格（CREATE/DESTROY/DELCONSUMER/
  CREATECONSUMER 共用一问）、类型闸排在存在闸之前两格（CREATE 与 DELCONSUMER 各一）、
  组不在的 NOGROUP 三格、五种字数形落回同一句、"照原样那一格"一格、DELCONSUMER 条数四格
  （`:2`/`:0`/`:0`/`:1`）加收尾 `GET`。
  全量 `mvn -o -B clean test`：**358 + 382 + 134 + 2 = 876，failures/errors/skipped 全 0**
  （`b59_full3.log`，core 从 381 抬到 382；提交前又在待提交的那三个字节上重跑一遍，
  `b59_full4.log` 同为 876 全绿、`rc=0`，跑前跑后三个文件的 md5 逐个不变
  —— `CommandHandler 2529fd07…`、`ConsumerGroup 91296cfc…`、测试 `00f15bd2…`）。
  **第一遍红在旧断言上**（`b59_full.log`：`Tests run: 382, Failures: 1`）——
  `streamFamilyHoldsTheSameOneTypeInvariant:1274` 钉的是 `XGROUP DELCONSUMER <刚凭空建出的消费者>`
  回 `:1`，钉的正是被本轮换掉的那个布尔答复。重钉成 `:0`（`RedisServerProtocolSemanticsTest.java:1273-1282`），
  并把"删没删掉"这件事改由 `XINFO CONSUMERS` 里 c9 不再出现来证 —— 旧断言的信息量不能一起删掉。
- **14 支具名变异，14 支全部点名判红**（`code_mut.py S`，S1-S14；快照 `2529fd07…` + `91296cfc…`
  逐支还原对账；S2-S14 与 S1 的第一遍记在 `xgroup_S_family.log`，S1 补对照后那一遍单独记在
  `s1_rerun.log`，末行 `还原: 2529fd07… 与副本逐字节同`）。S1 入口 arity 摘到 `< 1`、S2 MKSTREAM 那一问整块摘掉、
  S3 MKSTREAM 变大小写敏感、S4 "键必须存在"摘掉、S5 "组必须存在"摘掉、S6 那一问只认 DELCONSUMER、
  S7 类型闸挪到两道存在闸之后、S8 CREATE 分派松回 `< 5`、S9 只认 5 不认 6、S10 CREATE 的 ID 那一问摘掉、
  S11 条数在命令层窄化成布尔、S12 删了人不清账、S13 "照原样"退成大写、S14 分派大小写敏感。
  三条要说清楚：
  - **S1 第一遍 SURVIVED，且原因不是等价**：那三个字的 `XGROUP CREATE k` 与光杆 `XGROUP` 在改前
    没有任何一条断言读它们（光杆那一句在 1.3.5 起就是对的），所以摘掉 `< 2` 之后 2 条选取器用例
    照绿。补了两行对照（光杆吃命令表那句、三个字落回"认不得"）再打才 KILLED，
    红的是兜底 catch 的 JVM 文本（`-ERR internal error: Index 1 out of bounds for length 1`），
    与 R2/R4、Q5/Q10 同源 —— 入口闸同时是"不漏 JVM 文本"那一族的闸。
    这是 P5 那一条的第二种形态：**"没人读这行"与"这行改不动行为"是两件事，判红之前先问断言在不在**。
    重跑那一遍里同一批的邻居格 `streamFamilyHoldsTheSameOneTypeInvariant` 也报了错，报的是
    `startAndWait` 的 `BindException`（临时端口被本机代理抢走，成因与判据见"已知边界"里那一格），
    不是断言红。S1 的判定只认具名那一条（`xgroupGatesRunBeforeSubcommandDispatch:3148`，
    即"光杆 `XGROUP` 该吃命令表那句"），这条红的正是本轮新加的那一行对照；
    而未变异的树拿同一支选取器连跑 8 次全绿，所以"这一格的红"不是选取器本身带的。
  - **S5 的红是 `-ERR internal error: Cannot invoke "ConsumerGroup.destroyConsumer(…)"`**：
    摘掉"组必须存在"那一问之后 DELCONSUMER 拿着 null 组直接调，落到兜底 catch。
    这条不是"红得难看"，是那条闸还兼着防空指针 —— 所以 `CREATECONSUMER`（不在上游那份名单里）
    自己留了一问 `group == null → :0`，没让闸门替它兜。
  - **S7 证明"类型 vs 键存在"在 XGROUP 这一族也可观测**：被 String 占着的键在 store 那一层
    `getStream` 同样返回 null，顺序一换就会把 WRONGTYPE 答成"键必须存在"那句
    （`battery59:13 :23` 钉的是同一对，S7 两支红分别落在新旧两个方法里）。
- **当时记为"仍差一层"的那两格已在本节闭合**：`XGROUP SETID` 与 `XGROUP HELP` 的分派。
  上一轮之所以不敢顺手补，是因为 HELP 要先定"清单里能列哪几条"：本仓在 `DEBUG HELP` 那里立的
  口径是"只列真做得到的，照抄对岸那份等于对外承诺实现 segfault"（`CommandHandler.java:2269`），
  而把 SETID 列进 XGROUP HELP 就正好违反那条 —— 所以顺序必须是先兑现 SETID，再谈照抄清单。
  另有 `CREATECONSUMER` 是有意超出 5.0.14 的那一条（6.2 才有），本轮只让它共用闸门，
  "已存在的消费者回 :0" 仍未动 —— 我手上没有 6.2 的尺。

#### `XGROUP SETID` 兑现，以及 `HELP` 交回来的每一项是什么类型

- 权威仍是上游 5.0.14 的 `t_stream.c`（这一族没有参考实例：4.0.9 根本不认 stream 类型）。
  分派表里 `SETID`（:1891-1901）与 `HELP`（:1921-1922）两支本轮之前整条不兑现：闸门（上一轮）
  已经把键、组、类型三问答对，走到分派才落回"认不得的子命令"。
- **`SETID` 取 ID 用的是非严格解析**，本轮最实在的一条：:1895 是 `streamParseIDOrReply`，
  而 :1869 的 CREATE 是 `streamParseStrictIDOrReply`，两支只差 `strict` 那一个标志位
  （:1179-1180 那一问只在 strict 时才拦）。于是同一个 `-`：`XGROUP CREATE k g -` 回
  `Invalid stream ID specified as stream command argument`，`XGROUP SETID k g -` 回 `+OK`
  并把位置放到 `0-0`；`+` 同理展开成 `MAX-MAX`。`missing_seq` 两支都传 0，所以
  `XGROUP SETID k g 3` 是 `3-0`，不是"只换毫秒段、seq 停在旧值"——T5（只搬毫秒段）正是红在这里。
  `$` 那一支取 `s->last_id`（:1893-1894），且不像 CREATE 还需要"流不在就当 0-0"那一步：
  走到 SETID 时键必然在，闸门刚问过。
- **`HELP` 的每一项是状态串（`+`），不是 bulk（`$`）**。这一条不是从 5.0.14 读出来的，是在
  *有实例* 的那一侧量出来的：`addReplyHelp`（`networking.c:604-617`）交的是 deferred multibulk
  而逐项 `addReplyStatus`，实测对岸 4.0.9 的 `DEBUG HELP` 是
  `*["+DEBUG <subcommand> …;+segfault …]`（`battery32.zref:11`），而同一格我们一直是
  `*[$"…"]`（`battery32.zloc:11`）—— 差的正是类型那一个字节，按 RESP 类型分支的客户端会走错那支。
  也就是说这条漂移早在 string 族那几轮就该照出来，只是当时的断言只比了"是不是数组、有没有 sleep"，
  没比类型；本轮把它做成 `helpStatusArray` 一处渲染、两个 HELP 共用，并在对岸那一侧的
  `debugSubcommandGrammarMatchesTheReference` 里补钉 `*5` 与逐项 `+`。
  表头文案两边不同（4.0.9 实测 `… Subcommands:`，5.0.14 的模板是 `… Subcommands are:`），
  所以表头由调用方随清单一起给，不套同一个模板 —— 套一个就会把另一侧量错。
- 清单内容按 `t_stream.c:1800-1805` 那七行逐字钉住（含 `CREATE` 一条的续行），
  而 `CREATECONSUMER` **不列**：那是我们超出 5.0.14 的一支，列出去等于向 5.0.14 的用户承诺它没有的
  子命令。"只列真做得到的"那条口径到这里不再与照抄冲突，因为清单上那五条现在确实都做得到。
- **HELP 不躲取键那道闸**：:1826 的注释写着 "Everything but the HELP option requires a key"，
  而 :1837 的代码只看 `argc>=4`，没给 HELP 豁免。所以 `XGROUP HELP <不在的键>`（连命令名三个字）
  回清单，`XGROUP HELP <不在的键> <组>`（四个字）回"键必须存在"那句。按代码，不按注释。
- **顺带照出一个比 SETID 更早的病**：组的"最后投递位"是两个 uint64，而
  `18446744073709551615` 在 Java 里只能存成 `-1`。`ConsumerGroup.isNewerThanLastDelivered`
  过去先把两段拼成字符串、再让 `StreamEntry.compareIds` 解析回去，`"-1--1"` 在 `string2ll`
  那一步被"负数即越界"拒收（正是 `StreamIdFormat` 里"裸 `-1` 不收、带空格的 ` -1` 才收"那对
  不对称的下游），解析退成 `0-0` ⇒ 顶格位置读起来像流起点，**一条都不该投的变成整条流重投**。
  修法：两段带着位模式直接比（`StreamIdFormat.compare`），不再经过字符串。同一族的
  `Stream.java:205` 用的本来就是 `Long.toUnsignedString`，是对的 —— 全仓只有这一处在拼。
- **`>` 无可投时是 `*-1`，不是空表**：:1570-1585 判定不 serve，落 :1662-1664 的 `nullmultibulk`；
  而"读历史"（ID 不是 `>`）必给 `[[key, []]]`。两条判据在同一次测量里形成对照，
  这样"顶格位置之后没东西"不会被写成"整个请求被吞掉"。

- 量具与判红：
  - `battery60`（33 行：八格 SETID 写 + 六格跟随的 `>` 读 + 三格 HELP + 闸门/WRONGTYPE/对照）
    改前 `battery60.pre`、补分派后 `battery60.post` —— 翻 **15 行**
    （`:6 :7 :8 :10 :11 :12 :13 :14 :16 :23 :24 :25 :26 :32 :33`）。
  - 顶格那一处的修法单独量：`battery61`（13 行，四个组分别起步于 `MAX-MAX`、`2^63-0`、`0-0`、
    `1-MAX`）在**改 SETID 之前与之后逐行相同**（`battery61.pre` 与 `battery61.post` diff 为空），
    这一条把病因钉在 SETID 之外 —— 光 `XGROUP CREATE k g 18446744073709551615-…` 就能踩到。
    修完之后 `battery61` 翻 4 行（`:5 :7 :11 :12`），`battery60` 再翻 1 行（`:13`）。
  - 一处必须写下来的巧合：`battery60:13`（`SETID +` 之后读 `>`）在**改前的树上恰好与上游一致**
    （`*-1`）—— 那是两处错互相抵消的结果：SETID 未兑现 ⇒ 位置没动，位置本来就停在 `0-0` 之后
    又被上一轮读满。补了 SETID 而没修顶格比较时它反而变成"整条流"。所以"这一行改前就对了"
    不能当不动的理由，也不能拿它当这条修法的证据。
  - 协议层：`RedisServerProtocolSemanticsTest.xgroupSetidMovesThePositionAndHelpRepliesStatusStrings`
    把上面每一条走一遍（含七行清单逐项 `+`、`XGROUP help` / `HELP extra` 的同答复、
    四个字时的那道闸、以及 `1-MAX` 起步只投 ms 更大的那两格）。
  - 具名变异：`code_mut.py` 的 T 族 9 支（T1 分派、T2 arity、T3 严格/非严格、T4 的 `$`、
    T5 两段一起换、T6 HELP 分派、T7 渲染器类型、T8 闸前抢答、T9 把改前那一行原样装回），
    9/9 KILLED。T8 第一遍是 SURVIVED，因为插入点本来就在两道闸之后 —— 那是等价变异，
    换成真把 HELP 提到闸之前才红在那句"键必须存在"上。探针池 52→**61 支 / 62 个锚点**。


#### XPENDING 摘要的第 4 项：PEL 空时交的是"没有这一项"，不是"零个消费者"

上一轮把这两处形状量出来记在下面的"已知边界"里，这一轮把它闭上。权威仍是 5.0.14 的 `t_stream.c`：

- `:2059-2062` —— PEL 为空时 `start` / `end` 答 `shared.nullbulk`，第 4 项答
  `shared.nullmultibulk`。RESP 里 `*-1` 与 `*0` 是两个不同的值：`*0` 说"有零个消费者"，
  `*-1` 说"这一项没有"。按 null 分支的客户端收到 `*0` 会去遍历一个空列表，把"零"当成一个真实读数。
- `:2086` —— PEL 非空时 `if (raxSize(consumer->pel) == 0) continue;`：手上已经没货的消费者
  不出现在这份清单里。
- 改前的三行读数各自打自己的脸（`battery63.pre`，整份 10 行）：`:4`（组刚建、还没人读过）=
  `:0;$-;$-;*[]` —— 总账 0 条却交一份"零个消费者"的清单，该是 `*-1`；`:7`（c1 手上 1 条，
  c2 是 `XGROUP CREATECONSUMER` 造的 0 条）= `:1;$"1-1";$"1-1";*[[c1,$"1"],[c2,$"0"]]` ——
  清单里那条 `c2` 不该出现；`:10`（`XACK` 之后账已清而两个消费者都还在）=
  `:0;$-;$-;*[[c1,$"0"],[c2,$"0"]]` —— 一行同时违两条规矩，总账说 0 而清单挂着两条各 0。
- **顺带纠正 1.3.5 记的一句**：下面 `#### XPENDING 有三处对不上账` 写着"账上为 0 的消费者也照常
  列出（Redis 的汇总就包含它们）"。那句是假的，`:2086` 恰好相反 —— 汇总**不**列 0 条的，列 0 条的
  是 `XINFO CONSUMERS`（`:2568` 按 `raxSize(cg->consumers)` 整份列出）。历史那节不改写，以本节为准。
- 修法只做在命令层（`handleXpending`）：那道 `continue` 不能下沉进 `ConsumerGroup.perConsumerPending()`
  —— 同一份行数同时是 XINFO CONSUMERS 的计数来源，两个读者要两种形状。同一趟去掉两处已经接不上的
  null 兜底：`summary` 非 null 本身就已经是"键与组都在"的凭据，再走一遍 null 分支等于多造一条
  "组不见了却悄悄回一份空清单"的路径，而那条路上该发生的是报错。
- 顺带量出来的一件事：`perConsumerPending()` 里"给每个消费者种一条 0"那三行，与 XINFO 那侧的
  `getOrDefault(…, 0L)` 兜底**互为备份** —— 探针 U4（删种子行）与 U7（摘兜底）各单打都不红，
  两支一起打才炸（XINFO 的计数拿到 `null` 再 `intValue()` 就抛，`b63_U4U7_combined.log` 里两条
  `NullPointerException`）。所以这两处今天是一对冗余保护，而不是我上一轮写进注释的那句"共用一份
  口径就必有一边说假话" —— 那句被自己的量具当场证伪，三处注释已按实测改写。
- 新钉的形状（`RedisServerProtocolSemanticsTest.xpendingSummaryShapeFollowsTheReference`，
  逐元素读线上字节）：`*4`、总数是 integer、`start` / `end` 空时 `$-1`、第 4 项空时 `*-1`、
  消费者行是 `*2` 而里面的计数是 **bulk 串**（`:2089` 那句 `addReplyBulkLongLong`）。同一个现场
  还断言 `XINFO CONSUMERS` 必须仍看到 c2，而它那一格 pending 是 **integer**（`:2582` 的
  `addReplyLongLong`）—— 两个命令对同一个数用两种类型是上游故意的不对称，谁将来"统一"一下就红。
- 具名变异：`code_mut.py` 的 U 族 7 支（`python3 code_mut.py U`），**5 KILLED / 2 SURVIVED**
  （存活的那两支正是上面那对备份 U4 / U7，记为等价变异而不是缺口），探针池 61→**68 支 / 69 个锚点**。
  U1 与 U3 都会让"PEL 空"那一格回 `*0`，但红在不同段落：U1 被第一块钉住（那时还没有任何消费者），
  U3 只被最后一块钉住（账已清而 c1/c2 还在）—— 后面那一块才是"把判据写成有没有消费者"这种答法的
  照妖镜。U5 红在两支：新测试那句 `$1`，以及 1.3.5 就写下的 `contains("[c1, 2]")` —— 我原先以为
  `readReplyDeep` 把类型前缀抹平了，实测证伪：integer 它照样带 `:`，那一句一直对类型敏感。
  U6 才是改前真没人看过的一格（改前没有任何断言读过"空 PEL 时的 `start` / `end`"）。


#### XREAD / XREADGROUP 的选项那一圈：四句话各有它的位置，而 NOACK 以前根本进不来

上游把两个命令写在**同一个**函数里（`xreadCommand`，t_stream.c:1430-1486），选项是一圈扫描；
我们改前是两个手写的线性前奏，只认"COUNT、然后 BLOCK、然后 STREAMS"这一个固定顺序，
XREADGROUP 还要求 `GROUP` 必须是第一个词。顺序与判序在这一支里都是行为本身：

- `:1445-1449` —— `STREAMS` 之后的词数是奇数就报
  `Unbalanced XREAD list of streams: for each stream key an ID or '$' must be specified.`
  这一问排在**取键与查类型之前**，所以一份不配对的清单里就算混着 String 键，报的也是配对而不是
  WRONGTYPE（改前那一格答的是 `Invalid stream ID specified…`，也就是先把落单的那个数当成 ID 去解析）。
- `:1453-1458` / `:1462-1468` —— `GROUP` 与 `NOACK` 出现在 XREAD 里各有它的专有误用句，
  改前两句都是那句笼统的 `-ERR syntax error`。
- `:1475-1479` —— 整圈走完没见过 `STREAMS` 才是 syntax error。
- `:1481-1486` —— XREADGROUP 而 `GROUP` 没见过，报 `Missing GROUP option for XREADGROUP`，
  且这一问（:1483-1485 那三行）排在 `STREAMS` 那一问**之后**（探针 V5 就是换这一序，红在一个控制格上）。
- 两道 arity 下限没动，因为它们本来就是对的：`server.c:318` 的 XREAD 是 **-4**、
  `server.c:319` 的 XREADGROUP 是 **-7**。这两个数决定了哪些写法压根轮不到选项那一圈 ——
  `XREAD STREAMS k` 三个词是 arity 句而不是 Unbalanced，`XREADGROUP GROUP g STREAMS k 0-0`
  六个词也是 arity 句。
- **`NOACK` 改前根本不被接受**（`XREADGROUP GROUP g c NOACK …` 直接 syntax error），
  所以这个选项一直没法用。它只做一件事：**不记 PEL**（上游 :1468 置位、:1615 传下去、
  :1020 那一整块 PEL 写入被跳过）。组的投递位置照样推进（:990-992 排在那一问之外），
  消费者照样要被建出来并被 touch（:1610 用 `SLC_NONE`，而 :1745-1758 那个函数"查不到就顺手创建"）。
- 新读者 `RedisServerProtocolSemanticsTest.xreadOptionSentencesFollowsTheReference` 把上面每一句
  按线上文本钉一遍，并钉 NOACK 那三件兑现：投出去了（条目在里面）、`XPENDING` 仍回 `:0` 与 `*-1`、
  `XINFO CONSUMERS` 仍列出那个消费者、而再来一次 `>` 是 `*-1`（位置已推进，不重投）。
- 一处**没动也没验**的格子，写下来免得被当成已对齐："空 `>` 读 + NOACK 到底会不会把消费者建出来"。
  上游那一句 `streamLookupConsumer` 排在 `serve_synchronously` 那一支里面（:1596-1612），
  进不进得去由更早的条件决定，我没核实到那一层；两侧各有一处 `getOrCreateConsumer`
  （`StreamStore.xreadgroupNew` 与 `ConsumerGroup.markDelivered`），所以探针 V8 摘掉前一处也全绿
  （76 支里唯一的 SURVIVED，等价变异），与 U4/U7 是同一个形状的两个备份互兜。**不删任何一侧**，
  等拿到那一支的权威再定。


#### 就绪探针把"连上了"当作"起来了"：一把会替别人撒谎的尺（量具侧，不是服务器行为）

- 症状：本轮最后一次全量 `b64_full2.log` rc=1，`RedisServerLifecycleTest` 两例
  `expected: <+OK> but was: <HTTP/1.1 400 Bad Request>`（`:394` 与 `:653`），
  而**同一个树几分钟前刚全绿过**（`b64_full.log`），中间只有注释与行号引用的改动。
  两条的耗时是 `0.007 s` / `0.006 s` —— 这个数本身就否掉了"服务器起了又崩"：那需要真实启动。
- 三条独立取证：
  1. `grep -r 'HTTP/1\.1' / 'Bad Request'` 在本仓**零命中** ⇒ 这串字节不可能是 z-cache 发的；
  2. 两例**单独重跑绿**（`Tests run: 2, Failures: 0`，1.772 s —— 正常量级是百毫秒，反证 0.007 s 那一遍
     根本没起过服务器）；
  3. 同一时刻本机有**别的战役在跑 surefire**（`z-mq-broker` pid 18446 @ 03:30），端口是共享资源。
- 真因在量具：`freePort()` 用 `new ServerSocket(0)` 取到一个临时端口**再放开**，放开到 `RedisServer`
  真 bind 之间有窗口；`startAndWait` 的就绪判据是裸 `probe.connect(...)` 成功 —— 连上别人家的监听口
  也算"起来了"，于是后面每条断言都在读别人的响应。而循环里 `!thread.isAlive()` 那道闸排在 connect
  **之前**，第一轮时 bind 还没失败、线程活着，所以那道闸结构上拦不住这种抢占。
- 修法：探针要对方答一句话，且答的**首字节必须是 RESP 的类型标记**（`+ - : $ *`，带 `requirepass`
  时是 `-NOAUTH`，仍算 RESP）。`SocketTimeoutException` 单独走"继续等"那一支：内核已 accept 而事件
  循环还没读是我们自己起步慢，不许算成别人的端口。判据是 `grep 'probe.connect(new InetSocketAddress'`
  的命中面，共 4 处，已逐个改完并各自回读到 1 条新守卫
  （`RedisServerLifecycleTest` / `RedisServerProtocolSemanticsTest` / `RedisServerReferenceParityTest`
  / client 侧 `ZCacheClientIntegrationTest.awaitListening`）。`ZCachePoolTest` 那处 `new ServerSocket(0)`
  不带这种探针（它自己用 Netty 起了服务），本轮没动。
- **探针有牙，是注入同一现场量的 A/B**（`fake_http_400.py` 占住 57432 只答 `HTTP/1.1 400 Bad Request`，
  再把 `freePort()` 打成 `return 57432;`）：
  - 旧版（`git show HEAD:` 取的那份）⇒ `saveSnapshotsEveryDatabaseAndTheirTtls:394 expected: <+OK>
    but was: <HTTP/1.1 400 Bad Request>`，与 `b64_full2.log` 那一条**逐字相同、行号相同**
    （`b64_teeth_oldcode.log`）—— 这是复现，不是推测；
  - 新版 ⇒ `IllegalStateException: port 57432 上答话的不是 RESP，首字节 'H'(72) —— 端口大概率在
    freePort() 放开后被别的进程占走了`，落在 `startAndWait:781`，计为 Error 而非假 Failure
    （`b64_teeth_newcode.log`）。
  - 两支都从 `RLT.hardened.java` 副本 cp 回原文件，md5 `e2d7477d…` 对账；python 监听口 kill 之后
    `lsof -iTCP:57432 -sTCP:LISTEN` 复扫 0 条。
- 这条**没有**把"撞车"本身消掉：临时端口放开后仍可被别人拿走，改的是**归属** —— 从此撞车报的是
  "端口上答话的不是 RESP"，不再伪装成一条业务断言失败。要让全量在撞车下照样不红，得让 `startAndWait`
  换端口重来，那要动这 3 个类里所有用例的"先建服务器再取端口"结构，本轮没做。

#### XINFO 的两张表：`GROUPS` 少一对字段，而两张表的行序听的是哈希表

- 偏差是量出来的，不是读文档读出来的：`battery65`（36 行）对**改前那个 jar**
  （`b65_pre.jar`，里面 `CommandHandler.class` 的 md5 是 `8edaa85f`、`ConsumerGroup.class`
  是 `ba36351d`）与**改后那个 jar**
  （`b65_post.jar`，`5a32d6b1` / `8717c9b8`）各跑一遍，`zdiff.py` 报的原始账是
  **真实不一致 8 行 + 顺序不同 1 行**（`A=battery65.pre B=battery65.post 行数=36 顺序不同=1 真实不一致=8`）。
  那 8 行逐行对比后归两类：`:9 :14 :18 :22 :24 :28` 六行全是 `XINFO GROUPS`，差的就是
  `last-delivered-id` 那一对与行序（本增量修的）；`:12 :25` 两行除 `idle` 的毫秒数以外逐字节相同
  —— 那是现量时间不是行为，记为噪声，不当证据。"顺序不同"那一行是 `:35 XINFO CONSUMERS`。
- `XINFO GROUPS` 的一行是 **8** 个元素，不是 6：`t_stream.c:2600` 写的就是
  `addReplyMultiBulkLen(c, 8)`，第四对 `last-delivered-id` 在 `:2607-2608`
  （`addReplyBulkCString` + `addReplyStreamID`，后者发的是 `ms-seq` 的 **bulk 文本**）。
  改前那一行根本没有这一对 —— 而组位点恰恰是 `XINFO GROUPS` 存在的理由：客户端据此判
  "这个组读到哪了"，缺了它就只能靠 `XPENDING` 反推。
- 位点的渲染必须走无符号：顶格那两个 long 的位模式是 `-1L`，`String.valueOf` 会交回
  `-1--1`，而上游 `addReplyStreamID` 用的是 `%llu`。所以走 `StreamIdFormat.format(…)`，
  `XGROUP CREATE t 18446744073709551615-18446744073709551615` 之后回读的是同一串十进制
  （`battery65:28`、测试里 `top` 那一行）。
- 行序由表决定，不由建立顺序决定：两支都是 `raxSeek("^")` + `raxNext` 的顺序遍历
  （`:2568` CONSUMERS、`:2594` GROUPS），交回来的是**按名字升序**。改前 GROUPS 走插入序
  （实测 `zebra, Alpha, mid`），CONSUMERS 走的是 `ConcurrentHashMap` 的遍历序。
- **一把尺在这一格上撒了谎，是它自己得先修的**：第一版的 CONSUMERS 断言拿 `cA` / `cB` 命名，
  而这两个名字在哈希表里天然的遍历序**恰好就是升序** —— 改前改后逐字节相同，那一格等于没量
  （`battery65` 改前那 28 行里，CONSUMERS 只翻了 `idle`，没有一行是序）。做法是先拿
  `OrderProbe.java` 在 surefire 用的那个 JDK 上实测候选名的遍历序（不猜、也不照抄 python 里
  模拟的 CHM 哈希，那个模型当场就和实测对不上），再在 `battery65` 第 35 行用真服务器复现：
  `c2`、`c3` 由投递顺手建、`c1` 后建，改前那个 jar 交回的是 `c3 c1 c2`。测试与电池都换成
  这一组名字，`zdiff.py` 随即把第 35 行报成"顺序不同"，W4 也从"没读者"变成
  `expected: <$c1> but was: <$c3>`。**这条赌注是这次测到的，不是结构保证的** —— 换 JDK
  或换哈希之后 CHM 序有可能又恰好等于升序，那时 W4 会 SURVIVED，已写进下面"已知边界"。
- 名字比的必须是**码点**，不是 UTF-16 码元：rax 比的是 SDS 的字节，而 U+2B000 的头一个码元是
  `\uD86C`，按码元它排在 U+F000 之前，按字节它更大。新增 `ConsumerGroup.NAME_ORDER` 逐码点比，
  XINFO 两支的行序共用它；`perConsumerPending()` 的 `TreeMap` 也从自然序换过来 ——
  `XPENDING` 摘要第 4 项里的那些消费者行（`:2079-2089` 遍历的还是那棵消费者 rax）走的是同一条序。
- `XINFO GROUPS` 在没有任何组时交 `*0`，不是 null 数组：`:2590` 那一支在
  `s->cgroups == NULL` 时发的就是 `addReplyMultiBulkLen(c, 0)`。`RespArray.nullArray()` 发的是
  `*-1`，客户端把它读成"这个键不存在"。
- **自证是七支变异，全 KILLED**（`code_mut.py` 新增 W 族，日志 `code_W1.log`…`code_W7.log`，
  每支跑完都从本次快照按字节还原并 md5 对账，`CommandHandler.java` 回到 `bc555595…`、
  `ConsumerGroup.java` 回到 `12d8ca22…`）：W1 摘掉 `last-delivered-id` 那一对、
  W2 把位点按有符号十进制渲染、W3 把 GROUPS 的行序倒过来、W4 让 CONSUMERS 不排序、
  W5 把 `NAME_ORDER` 换成 `String::compareTo`、W6 让 `perConsumerPending()` 退回自然序、
  W7 让空组表交 `*-1`。七支红的都是这一条增量钉的东西，没有一支靠"编译不过"归红。
- **跑 W 族之前先修了尺自己的一处坑，两个受害者都是量出来的**：选取器里点名的
  `xreadOptionSentencesFollowsTheReference` 在类里根本不存在（真名没有那个 `s`），而
  surefire 对 `-Dtest=Class#不存在的方法`**不报错，只是那一条不跑**。同一条命令换名
  A/B 实测：假名那一遍 `RedisServerProtocolSemanticsTest` 的 tally 是 `Tests run: 1`，
  真名那一遍是 `Tests run: 2`（两侧 `BUILD SUCCESS` —— 它**不报错**，日志
  `b65_selector_ab.log`）。这个名字我错在两处：既有 `XOPT`（V 族八支的选取器）里一处，
  本轮新写的 `XINFX` 里另一处，两边各有一遍是在少跑用例的情况下报的 KILLED
  （W 族第一遍的 tally 是 `Tests run: 6`，改正后 `7`；那一遍的日志被第二遍按同名覆盖，
  留下的数只有下面 V1 这一遍可回读）。改正后 V1 重跑是 `Tests run: 23`
  （协议类 2 + `StreamTest` 21），仍 KILLED。
  并给量具加了一条 `code_mut.py selectors`：
  把 12 个选取器里点名的每个方法拿测试类源码逐个回读，认不出就 FATAL；这条尺的牙是**注入一个
  假名**验的（当场报出 4 条 FATAL）。V 族当时的结论不受影响 —— 那八支本来就是 KILLED，
  多带读者不会把 KILLED 变成 SURVIVED；受影响的是反向情形：少跑的读者会让 SURVIVED 被误读成
  "变异等价"，而真相只是"覆盖它的测试压根没跑"，那正是 P5 记过的同一个坑。

#### `XADD … MAXLEN 0`：0 是"清空"，而"没给"另有其人（-1）

- 这一格是上一格收口时顺手记进已知边界的，本轮把它从边界搬回修正。改前实测：`battery66`
  （30 行）对**改前那个 jar**（`b66_pre.jar`，由提交树 `1f55599` 重建，里面
  `CommandHandler.class` 的 md5 是 `5a32d6b1bdb6af6f90ae4123cdf3bee7`）与**改后那个 jar**
  （`b66_post.jar`，`49603ae429747d4cce0674066f92f598`）各跑一遍，`zdiff.py` 的原始账是
  **真实不一致 9 行**（`A=battery66.pre B=battery66.post 行数=30 顺序不同=0 真实不一致=9`，
  两侧各 `wrote=30 lost=none`，见 `b66_replay_pre_rows30.log` / `b66_postbuild.log`）。
  那 9 行里 `:19` 只是自动 ID 的那一毫秒（`$"1790454050844-0"` 对 `$"1790454251889-0"`，
  两边写的都是 `*` 那一格，逐字节比必然不同）—— 现量时间，算噪声，不当证据；剩下 8 行
  `:6 :7 :20 :21 :23 :25 :27 :30` 全是同一句话：**该空的地方没空**。
- 病在两个哨兵没对齐，不在裁剪算法：`Stream.trim(0)` 一直是对的（1.3.5 那轮还为它写过
  "MAXLEN 0 在 Redis 里是清空"的断言），坏的是它上面那一层 —— 改前 `StreamStore.xadd`
  的形参注释写的是"0 = 不裁剪"（`git show HEAD:` 回读，:81），条件写的是 `maxLen > 0`（:87），
  命令层也就没有"没给"这个值可传。上游是分开的两个值：`maxlen` 初值 **-1**
  （`t_stream.c:1240`，注释原文 "If left to -1 no trimming is performed"），负数在 :1268
  就被 `The MAXLEN argument must be >= 0.` 挡掉，所以到得了 :1327 的只有 `>= 0` —— 0 到得了；
  而且那一跳排在 :1321 的 `addReplyStreamID` **之后**，所以清空也要先把这一条的 ID 交回去
  （`battery66:5` 那一行两侧都是 `$"1-4"`，翻的是它后面那两行）。
- **"清空不等于删键"是这一支里唯一不显然的那一半**：上游的 `streamTrimByLength`（:424）
  只从 rax 里摘条目，`t_stream.c` 里一处 `dbDelete` 都没有（实测 `grep -c` = 0），
  表顶 `s->last_id` 也不动。于是 `XADD k MAXLEN 0 5-5 a 1` 之后再来一条 `XADD k 5-5 a 2`
  仍要吃"等于或小于表顶"那一问 —— 假如裁剪顺手删了键，这一条会被收下，同一个 ID 就能重发一遍。
  我们这一格改前就是对的（表顶存在 `Stream` 的计数器里，不在条目列表里），所以它在电池里是
  **对照组**（`battery66:28` 两侧逐字相同）而不是偏差；本轮给它补了一支变异（X4）来证明
  那两句断言真有牙，而不是"看起来对所以不用测"。
- 自证是四支变异，全 KILLED（`code_mut.py` 新增 X 族，日志 `code_X1.log`…`code_X4.log`，
  每支跑完都从本次快照按字节还原并 md5 对账，四支的还原分别是
  `d5f853d0…`（SS）/`72a11d40…`（CH）/`fecc074d…`（ST）/`fecc074d…`）：X1 把 `StreamStore.xadd`
  的条件退回 `> 0`（红在 java 层与命令层各一处，`改前这里是整条流的长度 … expected: <:0> but was: <:3>`）、
  X2 把命令层默认值换成 0（红在**上一轮**那条 `裁到 2 条，剩下最新的两条`：不带 MAXLEN 的
  XADD 也会把整条流清空，这一支的波及面比选取器宽得多）、X3 把 `Stream.trim` 的 0 当成不动
  （红在 1.3.5 那轮的旧断言 `MAXLEN 0 在 Redis 里是清空，不是不动`）、X4 让清空顺手把表顶抹回
  0-0（红在 `裁剪只摘条目，不动表顶` 与那句"等于或小于表顶"）。
  注入前重做过快照（`snapshot` @ 04:27，四个文件），因为 03:52 那次存的是**改前**字节，
  拿它还原会把本轮未提交的改动一起抹掉 —— 这也是 `TARGETS` 从三个文件加到四个的原因
  （`Stream.java` 这一轮第一次成为变异对象）。
- 依据档次要写清楚：我们改前答 `:1`/`:4` 是本机实测；"上游答 `:0`"是 :1240/:1268/:1327 的
  **源码推导** —— 参照实例 redis 4.0.9 根本不认 stream 类型，这一支没有可对拍的真值，
  与上一格同档。（`~` 近似裁剪仍然只是被跳过而不兑现，那一格留在下面。）

#### `XSETID key <id>`：一条都不在的流允许把 ID 空间退回原位

- 这一支在上游是六步（`t_stream.c:1931-1956`），本轮把六步逐个装到 `CommandHandler.handleXsetid`
  上，顺序与线位都按源码：`server.c:321` 的 arity 是**精确 3**（同族里只有它不带 `-`，
  `xadd -5`、`xgroup -2`、`xack -4`、`xpending -3`、`xclaim -6`、`xinfo -2`、`xdel -3`、
  `xtrim -2` 全在 `server.c:314-327` 那张表里，现在这张表本机可读，见下面"量具"那条）
  → `:1932` `lookupKeyWriteOrReply(…, shared.nokeyerr)`（键不在答 `-ERR no such key`，
  **不建流**，这一点与 XADD 的 `streamTypeLookupWriteOrCreate` 正相反）→ `:1933` 问类型 →
  `:1937` **strict** 解析（`streamParseStrictIDOrReply`，missing_seq 传 0）→ `:1942-1951`
  那道表顶闸 → `:1952-1953` 写 `s->last_id` 并答 `+OK`。
- 表顶闸这一格是这一支的全部难度：外面套着 `if (s->length > 0)`，比的是
  `streamLastValidID`（**还活着的最大学 ID**）而不是 `s->last_id`，条件又是 `< 0`
  而不是 XADD 那个 `<= 0`。三件事叠起来的结果是：**空流可以把 ID 空间往回挪**
  （这正是它的用途 —— 导入历史数据前先退回去），而有活条目时连 `0-0` 都要被拒。
  本仓 `Stream` 早就把这两个量分开了（`lastId()` / `lastValidId()`，上一格 X4 变异为它作过证），
  所以缺的只是把 `lastValidId()` 接到这道闸上，外加一个允许往回写的 `setLastId`。
- 改前实测：`battery67`（43 行）对改前那个 jar（`b67_pre.jar`，提交树 `1ea4260` 重建，
  `CommandHandler.class` md5 `49603ae429747d4cce0674066f92f598`）与改后那个 jar
  （`b67_post.jar`，`d10af081519025d9b83c42fb417a2241`）各跑一遍，`zdiff.py` 的原始账是
  `A=battery67.pre B=battery67.post 行数=43 顺序不同=0 (-) 真实不一致=27`（两侧各
  `wrote=43 lost=none`，见 `b67_replay_pre_rows43.log` / `b67_postbuild.log`）。
  那 27 行里 **20 行是 XSETID 本身**（`:3 :8 :10 :14 :15 :18 :19 :20 :21 :23 :26 :27 :28 :29 :30 :38 :39 :42`
  加 `:33 :35`），改前一律 `-ERR unknown command 'XSETID'`；另外 **7 行是下游证据**
  （`:5 :12 :22 :25 :34 :37 :43` —— 表顶挪过之后 XADD 的单调性判的就是挪后的位置，
  `XLEN` 也跟着变，这一类"命令自己不改、却把别人的答案改了"的行才是真正证明它写进去了的那一半）。
- 三条**对照组**要单独记，因为它们两侧逐字相同而含义不一样：`:40 :41` 是
  `XGROUP CREATE k67:s g67 0` 与 `XGROUP SETID k67:s g67 -` —— 非严格那一支本来就在（1.3.5
  那轮装的），所以同一枚 `-` 在 XGROUP SETID 侧答 `+OK` 而在 XSETID 侧答非法，**两侧都同答
  不等于两侧都正确**，它是"严格与否"这个标志位的参照；`:17` 的 `XAUTOCLAIM` 两侧都回
  unknown 而这一格**本来就是对的**（`server.c:314-327` 那张表里根本没有 xautoclaim，它是
  6.2 才加的）；`:16` 的 `XCLAIM` 两侧也都回 unknown，但那一格是**真缺口**（表里有，
  arity `-6`），留在下面的边界里。
- 自证是八支变异（`code_mut.py` 新增 Y 族，日志 `code_Y1.log`…`code_Y8.log`，每支跑完从本次
  快照按字节还原并 md5 对账：CH `c540de658a5addd5b3fc9f167b58ed9d`、ST
  `fc0889359abeda9c83b45afc76266804`，两支 ST 探针的还原同样逐字节同）：Y1 把 arity 放开成
  `< 3`、Y2 把 strict 摘成宽松、Y3 把表顶闸退回 `<= 0`、Y4 让那道闸去问 `lastId()` 而不是
  存活最大、Y5 把"键不在"那一回答成 `+OK`、Y6 把 ID 解析挪到两道取键闸之前、Y7 让
  `setLastId` 那一句空转、Y8 把 `setLastId` 改成只许往上涨。**八支全 KILLED。**
- **Y2 第一轮是 SURVIVED 的，而这一条值得写进账里**：当时那支测试只用 `$` 与 `*` 问严格与否，
  而这两个字在 strict 与非 strict 两支里都过不了 `string2ull`（`:1197`）—— 摘掉标志位
  一句话都不改，所谓"钉住了 strict"其实钉的是"这字不是数字"。上游那道 `:1179-1180` 只管
  `-` 与 `+`（`buf[1] == '\0'` 才拦），所以唯一的判别输入就是这两枚。补上
  `XSETID k -` / `XSETID k +` 与对照 `XGROUP SETID k g -` 之后 Y2 才红。
  写这一段的理由不是"后来修好了"，而是：**一条断言如果两侧同答，它就不许被读成"它区分了这两侧"** ——
  这一格上一轮（`:33` 那条 XPENDING 的教训）已经记过一次同形的，第二次踩在同一类句子上。
- 量具这一轮有一处实打实的升级：5.0.14 的**完整源码**已在
  本机 `~/.cache/zcache_gauges/full5/redis-5.0.14/src/`（从 `redis-5.0.14.tar.gz` 解出
  `t_stream.c` 与 `server.c`）。此前命令表读不到，arity 只能猜，于是 XSETID 这种
  "精确 3" 与 "至少 3" 的差别永远量不出来（`XSETID k 2-2 extra` 那一行回什么，取决于表里
  写的是 `3` 还是 `-3`）；现在它是一张可读的表（`server.c:314-327`），本轮的 arity 断言
  与 XCLAIM 的下一步都建立在这张表上。
- 依据档次与上一格同：参照实例 4.0.9 不认 stream 类型，所以"上游答什么"全部是**源码推导**，
  没有对拍真值；"我们改前答什么"是本机实测。



#### stream 键是键：`TYPE` / `EXISTS` / `DBSIZE` / `KEYS` / `SCAN` / `RANDOMKEY` / `RENAME` / `MOVE` / `FLUSHDB` 共用一把尺

- **上游只有一本账**：键空间就是 `c->db` 那一本 dict，stream 只是格子里装的 `type`。
  `dbsizeCommand` `db.c:808` 答的是 `dictSize(c->db->dict)`；`typeCommand` `:816-839` 是
  `switch (o->type)`，`OBJ_STREAM` 那一格 `:830` 交 `"stream"`；`renameGenericCommand`
  `:869-907` 搬的是 robj 指针（`incrRefCount(o)` `:886` → `dbAdd(dst,o)` `:898` →
  `dbDelete(src)` `:900`，过期时间在 `:887` 取、`:889` 挂到新键名上），
  **全程没有一个类型分支**；`moveCommand` `:919`
  同理；`flushdbCommand` `:432-437` 交回 `emptyDb(c->db->id, …)`（`:342`）把整本 dict 丢掉；
  `randomkey` `:524`（走 `dbRandomKey` `:235`）、`keys` `:536`、`scan` `:802` 三问遍历的
  也是同一本 dict。所以"stream 键对键空间不可见"在上游是问不出来的问题。
- **改前我们有两本账**（`battery68` 83 行两侧各跑一遍，`真实不一致=30`、`顺序不同=0`，
  逐行号与两侧原文见 `b68_diff.log`）：第 `3` 行 `TYPE k68:s` `+none`→`+stream`、
  第 `4` 行 `EXISTS` `:0`→`:1`、第 `5` 行 `DEL` `:0`→`:1`、第 `7` 行 `XLEN` `:1`→`:0`
  （`DEL` 说没删掉、`XLEN` 说还有条目 —— 同一枚键名两问两答的那一半）、
  第 `11` 行 `DBSIZE` `:1`→`:2`、第 `13` 行 `KEYS k68:*` 少一枚、第 `15` 行 `RANDOMKEY`
  回 `$-1` 而库里明明有流键、第 `83` 行 `SCAN` 只交一枚。
  第 `20`–`27` 行那一组是"顶牛"最直白的形状：`RENAME k68:s k68:t` 回 `-ERR no such key`，
  于是 `k68:t` 上一枚键名同时挂着流与 list，`GET` 回 `$-1`、`HGETALL` 回 `*[]`、
  `LPUSH` 回 `:1` 而 `TYPE` 回 `+list`（流那一半还在，谁都不肯先承认）。
- **修法只动一把尺**：`MemoryStore.DataType` 加第六型 `STREAM`，`typeOfDb` 现在也去问
  第六张表（`:220`），而第六张表的主人仍然是 `ServerScope` —— `RedisServer` 把自己那一份
  `StreamStore` 经 `bindStreams`（`:225`）**注入**给 store，而不是让 store 自己再 `new` 一份
  （一台服务器的流只能有一个主人）。**没接上注入的那两种用法**（单测直接 `new MemoryStore`、
  嵌入式自己组装）就维持注入前的形状、对 stream 一律答"没有这一枚键"，
  而不是凭猜测给一个数 —— 这一半由 java 层用例 `streamTableIsBlindUntilItIsBound` 钉住
  （它同时要求 `clearOtherTypes` 与 `flushDb` 在没接上的时候**不许**动到那张表）。
- **逐个读者复核**，一共六处（每一处都单独有一支变异）：`keysDb`（`:784`）、`scan`（`:816`）、
  `dbsizeDb`（`:873`）、`flushDb`（新增 `s.flushDb(db)`）、`moveKeyToDb` 的 `case STREAM`
  （`:1015`）、`clearOtherTypes` 的第六路（`:1137`，javadoc 那句"其余五种"连同代码一起改）；
  `CommandHandler` 这一侧三处：`deleteEveryType` 的第六路（`:636`）、`handleRename` 的
  `case STREAM`（`:1295`）、`streamTypeConflict` 那道闸现在放行 `STREAM`（`:2451-2456`）。
- **`RENAME` / `MOVE` 搬的是对象，不是条目快照**：上游 `dbRename` 换的是 dict 里的 value 指针，
  对象里装着一整条流，所以表顶、消费组、PEL 一起走。测出来的形状（第 `63`–`66` 行）：
  `XGROUP CREATE` + `XREADGROUP` 之后 `RENAME k68:g k68:gr` 改前回 `-ERR no such key`、
  改后 `+OK`，紧跟的 `XPENDING k68:gr grp` 改前回 `-NOGROUP No such key`、改后交出那枚
  未 ACK 的 `2-1`，`XINFO GROUPS` 改前回 `-ERR no such key`、改后 `consumers :1` /
  `pending :1` / `last-delivered-id 2-1` 三对字段都在。**第 `66` 行是这一格里唯一一条
  "改后新增的拒绝"**：`XADD k68:gr 2-1 a 9` 改前答 `2-1`（那一句 `RENAME` 根本没成，键名
  `k68:gr` 上还没有流，`XADD` 是从零起凭空建出一枚新流），
  改后回 `-ERR The ID specified in XADD is equal or smaller than the target stream top item`
  —— 表顶跟着搬过来了，2-1 就不再"比表顶大"，这正是上游搬指针该有的后果。
  `MOVE` 那一组（第 `74`/`77`/`78`/`79`/`80` 行）同理：改前 `:0` 且目标库三问全空，改后 `:1`
  且目标库 `EXISTS :1` / `TYPE +stream` / `XLEN :1`，源库 `EXISTS` 归 `:0`。
- **两处洞是我这把新尺自己暴露出来的，不是既有偏差**：① `flushDb` 原本不清第六张表 ——
  只抬 `DBSIZE` 不抬它就是"清库之后 `DBSIZE` 归零而旧表顶还压着"，`FLUSHDB` 之后
  `XADD 1-1` 反而吃"ID 太小"（第 `56` 行就是这一问的对照，改前 `XADD k68:f 1-1 a 1` 回
  ID 太小而改后交出 `1-1`）；② `clearOtherTypes` 的 javadoc 写"其余五种"，漏的正是第六张表。
  Z8 打 ①、Z7 打 ②（判反之后 `SET` 顶不掉流，一个键名两半并存，`DEL` 只删得掉一半）。
- **一条量具的自新：第一版 Z6 打在死代码上**。`MemoryStore.randomKey(int)`（原 `:1023`）
  全仓 **0 读者**（`grep -n randomKey` 实测只有定义那一行），RANDOMKEY 的活路径是
  `CommandHandler.handleRandomkey` → `store.keysDb`。于是那支探针 `mvn rc=0`、
  **SURVIVED** —— 而这**不是**"等价变异"，是量具自己骗自己：我拿一支"改了谁都不看"的探针
  当"这一格有牙"的证据（日志留在 `b68_Z_family.log`，改指向之后是 `b68_Z_family2.log`）。
  收法是**删掉那个方法**（不替它找读者、也不为它编断言），Z6 重新指向活路径：把随机池
  换成空表 ⇒ 红在 `expected: <ks:s> but was: <$-1>`。顺带删掉的还有 `handleRandomkey` 里
  `keysDb` 之外又并一遍 hash/list/set/zset 的**第二次枚举** —— 那四种键因此在随机池里被数
  两次、抽中概率翻倍，而 string/stream 只算一次；上游 `dbRandomKey`（`db.c:235`）在同一本
  dict 上取一格，每枚键等权。**这一条没有量具**：抽样是随机的，`ThreadLocalRandom` 没有
  可注入的接缝，83 行对拍改前改后逐行相同（`battery68.post2` vs `post3`，`真实不一致=0`；
  改后 jar 的 `CommandHandler.class` md5 `7e09e92b8c4e6c4d7ea2dc18c67c9d83`，
  本轮改前那一份是 `2f8e8f5867a7277c7a69f8d769e62f5c`）只证明"内容没变"，
  证明不了"权重回到等权"。记在**未覆盖**里。Z5 的锚点注释也跟着作废了一半（它当初带上
  下一行是为了躲 `randomKey` 里逐字相同的那一句），锚点仍然唯一、仍然红，理由换掉了。
- **已知不一致，钉现状而不是钉成合格**：TTL 一族还不会问第六张表。上游
  `expireGenericCommand`（`expire.c:415-451`）唯一的存在性闸是 `:426` 的 `lookupKeyWrite`，
  **没有类型分支** —— 所以 `EXPIRE` 打在流键（以及一切集合键）上回 `:1` 并真的挂上过期，
  我们回 `:0`。用例末尾把 `TTL :-2` / `EXPIRE :0` / `PERSIST :0` 三行按现状钉住，
  并拿一枚 **list 键**做对照（同一句在 list 上也回 `:0`），证明它是集合族共有的旧缺口、
  不是 stream 这一支新引进的回归。
- **取证一处，错在我这侧**：`RENAME` 之后 `XACK` 清空 PEL 那一问，我先按"第 4 项是空数组"
  写期望，读了 `t_stream.c:2056-2062` 才改对 —— 上游交的是 `nullbulk`/`nullbulk`/
  `nullmultibulk`，线上形状是 `[:0, $-1, $-1, *-1]`。实现本来就对，改的是断言。
- **自证**：`code_mut.py` 的 Z 族 15 支（Z1-Z15）**15/15 KILLED**，十五次还原逐字节对账
  （CH `bde83a2252efb01f73d8fa7ed8703e93`、SS `832816457d9567fa19f324193174709d`、
  MS `35bb2d879b816b74607d3ad7af9a2ec7`、RS `13ae0ecda5a537be2f3305d24c2c6fd6`）。
  红句互不相同：Z3 `expected: <:6> but was: <:5>`、Z14 "源键整个没了，不是留了一份副本"、
  Z15 "源库里已经没有"、Z13 `expected: <+stream> but was: <+none>`。Z10 是唯一一支选取器
  只点协议层（`bindStreams(this.scope.streamStore())` → `bindStreams(null)`）—— 它量的就是
  接线本身，摘掉之后协议层全塌而 java 层照旧绿，所以那一支**不该**带 java 层的用例。
  量具到 **110 支探针 / 111 个锚点**（Z 族 15 支是这一轮加的，之前 95 支），
  TARGETS 新增两本文件：`MemoryStore.java`、`RedisServer.java`。



#### 测试取号压在操作系统出站区间之下：9 遍全量里 2 遍的假红（量具侧，不是服务器行为）

- **现象**：`RedisServerLifecycleTest` 每隔两三遍就有一遍在 `startAndWait` 抛
  `IllegalStateException: server thread died before listening on <port>` +
  `java.net.BindException: Address already in use`，而红的用例不固定
  （`b68_full4.log` 是 `saveWithoutDataDirFailsHonestly:478`、`b68_full8.log` 是
  `blpopPopsOnlyRequestedKeyWithoutFreezingPeers:123`）。分母：上一格那轮改动期间全量跑 9 遍、红 2 遍。
- **归因**（不是"运气不好"，也不是上一格改坏了）：`freePort()` 写的是 `new ServerSocket(0)` ——
  探一个号码、关掉、再交给服务器去 bind。`bind(0)` 交回的号码出自操作系统的**出站**派发区间
  （macOS 49152-65535、Linux 默认 32768-60999），探针一关，这个号码立刻重新可派给一条新起的出站
  连接，而本机常驻代理一直在起。`lsof -nP -iTCP:64088` 在红过之后当场抓到那枚号码挂在 `verge-mih`
  的一条 `FIN_WAIT_2` 出站连接上 —— 服务器要 bind 的时候，那个号码已经是别人的一条客户端连接了。
  **下面那条守卫顺带把这个机制复现了一次**：把 `freePort()` 原样退回旧写法，它第一次取样就拿回
  `61194`。
- **修法**：在一个固定的低位窗口 `[20000, 30000)` 里，顺着一个随机起点的游标找一枚真能 bind 上的
  号码。窗口之下的号码操作系统不派给出站连接，"探得到"与"bind 得上"之间那道窗口就不存在了；
  窗口里撞上别人的常驻服务照常换下一枚（本轮 `lsof -nP -iTCP -sTCP:LISTEN` 实测窗口内确有一枚
  `*:25170`，它占着的是 LISTEN 而非出站连接，换号即可）。改前四份 `freePort()` 是**逐字相同**的
  （各 169 字节，md5 四份同为 `35f332c3799bf2edd8a50280de161a8d`，`git show HEAD:<路径>` 逐一量过），
  所以一起换：
  `RedisServerLifecycleTest`、`RedisServerProtocolSemanticsTest`、`RedisServerReferenceParityTest`
  加上 client 侧的 `ZCacheClientIntegrationTest`。**改后只有三份逐字相同**
  （`890c90f3b675009ecb32471828edcee2`），client 那份是 `3918efcef7aeed75a5e008f592ef766b`，
  与前三份的差别只在一处限定名 —— 那个文件 import 了 `InetSocketAddress`，所以 bind 那一行
  不带包名前缀。这句话要说清，否则"四份相同"在改后就不成立、下一个人按它去核对会核不出。
- **为什么还要单独测一层**：`startAndWait` 里早就写着"先 bind 一个临时端口再放开"这段窗口的注释，
  可它只把**就绪判据**从"连得上"改成"答的必须是 RESP 形状" —— 那一半挡的是"读到别人家的响应"，
  没挡"自己根本 bind 不上"。两半都要在，注释里点明了这一层。
- **结构守卫**：`RedisServerLifecycleTest.probePortsComeFromBelowTheEphemeralRange` 连取 20 枚，
  两问都断言 —— 号码要落在 `[PORT_BASE, PORT_BASE + PORT_SPAN)`，还要低于两侧最小出站区间的下界
  32768。断言的是**取号方式**而不是"这枚端口能 bind"：后者新旧两版都成立，挡不住退回。
  双向实测过：注入旧写法 ⇒ 红在
  `取号窗口要一眼看得出来：61194 不在 [20000,30000) 里`（`logs/portguard_revert.log`，rc=1，
  1 例 1 红）；还原是从 `/tmp/RLT.orig.java` 按字节 `cp` 回来并 md5 对账
  （`bf1691d117ae4f3cfe1476b53784b521`，注入前后逐字相同）—— **不走 `git checkout`**，
  这些文件上还有我没提交的改动，HEAD 不是"我开始测量那一刻"。
- **这层守卫只护得住一份**：它读的是本类的 `freePort()`，另外三份是各自独立的私有静态方法，
  退回旧写法时这一支不会红。跨模块没有可行的接缝（core 的测试看不见 client 的类，也不想为此
  造一个生产侧的取号工具），所以那三份目前只有"改后 12 遍全绿"这一条统计证据 + 上面那组 md5
  记账。记在**未覆盖**里。
- **改后重测**：全量**连跑 12 遍，12 绿 0 红**，`Address already in use` 在 12 份日志里合计 0 命中
  （`logs/portfix_p1.log` … `portfix_p12.log`，每遍都是 `358 + 392 + 134 + 2 = 886`，
  core 从 391 抬到 392 = 那条形守卫）。统计口径要摊开说：改前 2/9 ≈ 22%，若抖动仍是 22%，
  12 遍一次不中的概率是 `0.78^12 ≈ 4.6%` —— 这是强证据，**不是证明**。
- **同一个来路还剩一处，这一票没做**：`ZCachePoolTest.startEmbeddedServer`（client 模块）自己写了
  第五份 `new ServerSocket(0)`（它那份不叫 `freePort()`，所以前面那批逐字相同的漏了它），探完关掉
  再让 Netty 去 bind 同一个号码；而它 catch 里的退路是 `testPort = 16379` —— 一枚**写死端口**，
  跨跑互撞时的表现是"起不来"或"读到上一轮残留"。已登记为下一票，**下一票已跑完**：见下面
  《让 listen socket 自己挑号码》那一格。

#### 让 listen socket 自己挑号码：取号这一族在这里可以连根拔掉（量具侧，不是服务器行为）

- **来路**：上一格登记的那一处 —— `ZCachePoolTest.startEmbeddedServer`（client 模块）自己写了
  第五份 `new ServerSocket(0)`，探完关掉再让 Netty 去 bind 同一个号码；而它 `catch` 里的退路是
  `testPort = 16379`。这一处的退路比窗口更糟：窗口只在"代理恰好抢走同一枚号码"时发作，
  退路则是一枚**写死端口**，本机同时跑两遍 surefire 就是同一条地址，
  或者更糟 —— 连上上一轮残留的那台。
- **修法**：`b.bind(0).sync()`，然后从**已经 bind 上的那只 channel** 读回号码
  （`ZCachePoolTest.java:74` 与它后面那一行）。这里不需要"低位窗口"那套绕法：
  号码由 listen socket 自己拿，"探一枚、放开、再交给服务器 bind"那道空隙**根本不存在**，
  所以操作系统没有转手的机会。`catch` 整段删掉，16379 也随之从测试树消失
  （`grep -rn 16379 --include='*.java' */src/test` 现在只剩我自己那两句注释）。
- **为什么这一处能连根拔而四份 `freePort()` 不能**：这个类自己握着 Netty 的 `ServerBootstrap`，
  读回端口是它手上那行代码的事；而 `RedisServer` 明确拒收 `port 0`
  （`RedisServer.java:98-100`：`if (port < 1 || port > 65535) throw new IllegalArgumentException`），
  也没有"起完之后我实际听在哪一枚"的可读出口。真要按这条路收，得先给它加 `boundPort()`，
  再改测试侧 **71** 处构造点（`grep -rc "new RedisServer("` 实测：
  `RedisServerProtocolSemanticsTest` 48、`RedisServerLifecycleTest` 20、
  `RedisServerReferenceParityTest` 2、`ZCacheClientIntegrationTest` 1；另有 main 侧 1 处不算）。
  上面《已知边界》里原先写的"约 25 处"是估的，这次量出来了，已按实测改正。
- **回读的号码落在出站区间里，这是对的**：两次实测拿回的是 `55974`、`56043`，都在 macOS 的
  49152-65535 里。缺陷从来不是"号码在哪一段"，而是"号码有没有一秒钟不在自己手上"。
  上一格那条守卫断言 `port < 32768`，管的是**探针**交出去的号码，两件事别混。
- **守卫**：`ZCachePoolTest.advertisedPortIsHeldByOurOwnListeningSocket`（`:107`）三问 ——
  号码合法、`assertEquals(testPort, listen socket 的号码)`（`:115`）、以及第二只
  `setReuseAddress(false)` 的 socket 去 bind 同一枚**必须失败**（`:119`）。第三问是"这台服务器
  真的在听我们 advertise 的那一枚"，与前面几格里"就绪的判据不能只是连得上"同一个来路。
- **两支注入，各打中一问**（都是 `cp` 备份 + `cp` 还原 + md5 对账，**不走 `git checkout`**；
  还原当时 `/tmp/ZPT.orig.java` 与盘上文件同为 `afc41ece8c09ba1df3b2babef3a9bf81`。
  那 7 遍量完之后这个文件又改过一次**守卫方法内部的注释**（判据一条没动，改动前的注释里
  "旧写法在这里读不回号码"那句说过头了 —— 旧写法在号码没被转手时两问都会等值通过，
  真正咬得住的是"回读的号码是不是 listen socket 手上那一枚"），提交时的字节是
  `7140d5d2f2205b5baba0b7cb2658ee67`，下面那两遍全量是按这一版字节重跑的）：
  - **A**（`testPort = 16399`，模拟旧写法的固定退路）⇒ 红在
    `回读的号码不等于 listen socket 实际拿到的号码 ==> expected: <16399> but was: <55974>`
    （`logs/zpt_mutA.log`，rc=1，1 例 1 红）。
  - **B**（把第三问的 bind 目标改指到一枚 `lsof -nP -iTCP:16399` 验过空白的号码，
    即"这号码不在我们手上"）⇒ 红在
    `第二只 socket 竟然 bind 得上 …`（`logs/zpt_mutB.log`，rc=1，1 例 1 红）。
    这一支**证的是那句 `fail(...)`  reachable、不是恒绿**：本机上一枚没人在听的号码确实 bind 得上。
    顺带说清一处读日志时容易误会的细节 —— 那句消息里印的是 `testPort`（`56043`），
    而注入只换了 bind 的目标号码，所以"红句里的号码"和"注入指的号码"在这支里**故意不相等**。
- **改后重测**：全量**连跑 7 遍，7 绿 0 红**，`Address already in use` 在 7 份日志里 0 命中
  （`logs/zpt_full1.log` … `logs/zpt_full7.log`，每遍都是 `358 + 392 + 135 + 2 = 887`，
  client 从 134 抬到 135 = 那条守卫；`ZCachePoolTest` 单类从 22 例到 23 例）。
  那 7 遍之后只剩一处注释改动，于是**按提交的字节又重跑 2 遍**：`logs/zpt_final2.log`、
  `logs/zpt_final3.log` 都是 `887 / 0 失败 / BUILD SUCCESS / Address already in use 0 命中`。
  这一处从此没有窗口，所以它不需要像上一格那样"靠多跑几遍来赌" —— 9 遍只是不退别的判据。

#### 过期时刻长在值对象上：五种集合键的 TTL 不是"忘了接线"，是结构上没地方挂
- 现状：`EXPIRE myhash 100` 回 `:0`、`TTL myhash` 回 `-2`，五种集合键全一样。那面 fence
  （`RedisServerProtocolSemanticsTest:4141-4156`）钉的是现状，并且自己写明了"这一格缺口在
  list 键上，与 stream 无关"。上游 `expireGenericCommand`（`expire.c:415-451`）问的从来不是
  "这是什么类型"，唯一的存在性闸是 `:426` 的 `lookupKeyWrite`，时刻存在每个库自己的
  `db->expire` 字典里。我们这边时刻是 `MemoryStore.ValueWrapper` 的一栏，而这个对象只活在
  `stringStores` 里 —— 所以 command 层不是"忘了接第五张表"，是**接不上**：集合键没有那个对象可挂。
- 这一格只做搬迁，**不改任何可观察行为**（判据见下面"量具"那一杠）。
  - 新增 `expirations[db]`（键名 → 绝对毫秒），时刻从此是键空间的属性，与值是什么类型无关；
    `ValueWrapper` 去掉 `expireAt` / `isExpired()` / `hasExpiration()`，构造器收成单参。
    13 处构造点全在本类内；值外面只有两处在读它（`MemoryStoreAccessor:41`、`:109`）。
  - **谁收记录**：23 处 `clearExpireAtDb`，一条"键没掉"的路都不许漏 —— 惰性删除那八处
    （`checkKeyType` / `typeOfDb` / `getDb` / `ttlDb` / `pttlDb` / `existsDb` / `keysDb` /
    `dbsizeDb`，加 `getLiveWrapper`）、`DEL`（两支）、`FLUSHDB`、淘汰（两处）、`dbOverwrite`
    （`clearOtherTypes` 的 STRING 支）、整键覆盖三处（`SET` / `SETNX` / `GETSET`）。
    整键覆盖要把过期一起清掉，权威是 `setKey`（`db.c:216-224`，`removeExpire` 在 `:223`：
    "The expire time of the key is reset (the key is made persistent)"）。
  - **谁一句都不必写**：`APPEND` / `SETBIT` / `SETRANGE` / `INCR` / `INCRBYFLOAT` 五路原地改写。
    以前它们靠"新 wrapper 带着旧 `expireAt` 顶掉老 wrapper"来留 TTL —— 那是这个设计里唯一
    留得住的办法；时刻不在值上之后，什么都不做才是对的，所以这五处反而各删掉了一句。
  - `getAllExpirationEntries` 改成遍历时刻表（不再遍历 string store）。那道 `isExpiredDb`
    的闸留着，理由是它与 `getAllStringEntries:41` 是同一把尺 —— 值那一半不把过点的键交出去，
    时刻这一半也不交，否则两边给出的键集对不上。
- 搬完才露出来的一个形状，这一格顺手改掉：`putDb` 装到上限时会**当场淘汰一个键**，而被淘汰的
  完全可能就是刚写进去的这一枚。于是"先写值、后记时刻"会给一枚已经不存在的键留下一行记录；
  长在值上的年代不会有这个形状（键没了时刻跟着没）。现在四处登记（`setex` / `psetex` /
  `expire` / `pexpire`）一律排在 `putDb` **之前**，`evictOne` 那句回收才轮得到它。
- 无主记录今天够不到协议层，也够不到存档 —— `RdbPersistence` 是遍历五张值表、再按 key 去
  `getOrDefault` 查时刻的（`RdbPersistence.java:463-467`），没人查的行不会被写进 dump。
  它的代价是内存，以及"下一格把集合键的 TTL 接上之后，这行会凭空挂到下一个用这个键名的键身上"。
  也正因如此，这一问在上面那几层**看不见**，只能在 store 层量。
- 量具：`MemoryStoreTest` 加五支（该类 40 → 45 例），全量 `887 → 892`
  （`358 + 397 + 135 + 2`），提交的字节连跑 2 遍全绿（`logs/ttlfinal1.log`、`logs/ttlfinal2.log`）。
  五支的写法是"每走一条删除/覆盖/搬移的路，立刻量一次不变式（时刻表里每一行的键名还得是六种
  类型之一）"，并且中间夹了三处正面对照（挂上过期后表里必须有这一行、时刻必须在未来、
  `PERSIST` 回 true 才准摘）—— 没有那三处，一把恒空的表就能把前四句全骗过去。
  四支注入各打一条路（`~/.cache/zcache_gauges/ttl_mut/teeth.py`：注入前先 `cp`、每支还原后按
  md5 `d4a4425f8f32c67e3a46f0f2053ccb7a` 对账，基线在同一份字节上跑过并且绿）：
  摘 `evictOne` 的回收 → 红在 `写五十次淘汰四十多次，表里不该有五十行`；
  摘 `delDb` STRING 支的回收 → `delAndFlush…` 与 `expiryRecord…`（EXPIRE 0 那步）两处红；
  摘 `setDb` 的覆盖清除 → 红在 `SET 覆盖后不许留着旧时刻`；
  把 `psetexDb` 的登记挪回 `putDb` 之后 → 红在 `淘汰之后：时刻表里给一个已经不存在的键名留着
  过期记录 -> k31`（那个号码每遍不同：挑谁是受害者是随机的，别把它当固定期望）。
- 还没做的两件事，写在这里免得下一格漏：
  1. **这一格没有让任何一种集合键的 TTL 活起来。** `expireDb` / `ttlDb` 那五支仍然只看
     `stringStores`，上面那面 fence 原样留着，是下一格要翻的。
  2. 四个集合 store 自己的 `del`（`CommandHandler.deleteEveryType` 直接调它们）不碰时刻表。
     今天没有集合键的行可收，所以那一句在这四处是**空转**、也不在任何判据里 —— 记在**未覆盖**。
     下一格要么让删除统一走一个"六型通删"的口，要么这四处各补一句。
     **（已收：走的就是"六型通删"那一个口，见下面《六型通删》那一节。）**
- 这一格原先还列了第 3 条"待修缺口"，**那条是我写反的，在此改口并留下证据**。原文：
  "`GETSET` 仍然把 TTL 抹成永久……而上游 `getSetCommand` 用的是 `dbOverwrite`
  （`db.c:189-206`，不清过期）"。对着 5.0.14 源码复核的三点：
  1. `getSetCommand` 这个符号上游**不存在** —— 全 src 目录只有 `getsetCommand`
     （`t_string.c:176`，命令表注册在 `server.c:227`）。写文档时我没 grep 过它，只凭形状编了个名字。
  2. `getsetCommand` 换值用的是 `setKey`（`t_string.c:179`），而 `setKey`（`db.c:216-224`）
     的文档第三条正是 "The expire time of the key is reset (the key is made persistent)"，
     `removeExpire(db,key)` 就在 `:223`。**GETSET 抹掉 TTL 就是上游行为**；
     `dbOverwrite`（`db.c:189`，文档 "does not modify the expire time"）是另一批路径用的
     —— INCR / INCRBYFLOAT 那两处（`t_string.c:364`、`:416`），对应的正是我们
     那五支就地改写、不碰时刻表的实现。
  3. 所以 `getAndSetDb` 里那句 `clearExpireAtDb` 不是要摘的，是要钉的。缺的不是修复而是判据：
     `CommandHandlerJunit5Test.testGetsetResetsTheTtlTheWayUpstreamSetKeyDoes`（SETEX →
     `TTL > 0` 正面对照 → GETSET 回旧值 → `TTL` 回 **-1**（键在、不过期）而不是 -2（键没了）→
     GET 回新值）。摘掉那句 `clearExpireAtDb` 的注入红在
     `GETSET 之后回 -1（键还在、不过期），不是 -2（键不存在） ==> expected: <-1> but was: <100>`
     —— 基线与还原 md5 同为 `d4a4425f8f32c67e3a46f0f2053ccb7a`，同一份字节上基线绿
     （`~/.cache/zcache_gauges/ttl_mut/getset_teeth.py`）。
  把这段错账留在文档里而不只是删掉，理由很直接：一句话读起来像"待修缺口"，下一格就会照着动手，
  动手的结果是把正确的行为改成 bug。

#### 六型通删：惰性删除与"删干净"从此只有一把尺，而且它问得到六种类型
- `MemoryStore.removeAnyType(db, key)` 新增：一个键名下六张表全清一遍，连它在时刻表里的那一行
  一起回收，回"本来有没有一个还活着的键"。`CommandHandler.deleteEveryType`（DEL / RENAME /
  `S*STORE` 那六个入口共用）从五行各调一家收成一句委托。
- `typeOfDb` 的惰性删除换成了类型无关的：先问时刻表（`hasExpirationDb && isExpiredDb`），
  命中就 `removeAnyType`。以前那一段只认 `stringStores` 里那一张表。
- 权威：`expireIfNeeded` 不看类型（`expire.c:415-451`，`:426` 只有 `lookupKeyWrite` 一道闸），
  它删的是整个键（`dbSyncDelete`），不是"只抹时刻、键留着"。
- 为什么上一格搬不动、这一格搬得动：上一格把时刻从值对象搬进"按库、按键名"的一张表，
  六种类型这才可能有共用的"删键"落点；上一格末尾记的那条未覆盖（四个集合 store 的 `del`
  在这一问上是空转）就是这一格收的。
- **对外一行行为都没变**：全量 `893 → 895`（`358 + 400 + 135 + 2`，`logs/sixtype_full1.log`，
  判据字节 md5 `73e9c74a9388f9aad17a2d307bc37195`）。今天没有任何一枚集合键能有时刻行
  （`expireDb` 那五支还只看 `stringStores`），所以那段通用惰性删除对五种集合类型仍是一次空问 ——
  它是下一格的承重墙，不是这一格的收益。
- 一次踩坑又爬出来的记录：第一版把 `deleteEveryType` 整段委托掉，会把 stream 那一路删坏。
  协议层的 `streams()` 在没有 scope 时退回进程级 static 那一份（`CommandHandler:76` 的
  `defaultStreamStore`，`setStreamStore` 在 `:116`），而 `MemoryStore` 只认 `bindStreams`
  进来的那一份（`RedisServer:116`）。生产的两处构造点都绑了 scope（`RedisServer:173-177`
  连接流水线、`:249-250` AOF 重放），所以那两处是同一份、委托不会漏；而 static 兜底那一份
  **没有任何生产调用者**（全仓 `setStreamStore` 只有 `CommandHandler:116` 那一条定义，另两处命中
  都是注释：`RedisServerHandler.java:56`、`RedisServerLifecycleTest.java:24`）。
  兜底腿因此留在协议层原样问一遍（两份都问是幂等的）。留着它是"不改行为"，
  **不是"它被量过"**：这条腿今天零测试覆盖（三个裸 `new CommandHandler(store)` 的测试类里
  XADD 计数为 0）。
- 判据：`MemoryStoreTest` 45 → 47。两支各钉一条新路，都拿单行变异验过牙
  （`~/.cache/zcache_gauges/ttl_mut/sixtype_teeth.py`：注入前 `cp`、每支还原后按 md5 对账、
  基线在同一份字节上绿）：
  - 把 `removeAnyType` 摘成"命中一腿就 return" → 红在
    `反过来只删 hash、留下 string 也一样 ==> expected: <false> but was: <true>`
    （钉的是同一键名两张表并存那一问 —— 逐型删除那六问反而摘不到，因为它们各自只有一张表有键）；
  - 摘掉 `typeOfDb` 里惰性删除那一段 → 红在
    `到点了，这一问要回 none —— 而不是「键还在，只是过期了」 ==> expected: <NONE> but was: <STRING>`。
  **第二支第一遍是 SURVIVED 的**：那一次整套 core（当时 398 例）全绿。原因是
  `getDb` / `existsDb` / `keysDb` / `dbsizeDb` / `ttlDb` 五家各自还带着自己的惰性删除，
  任何一家先被碰到，`typeOfDb` 这一问就被掩盖掉了。补的那支因此刻意**只问 `typeOfDb` 一把尺**，
  并且直接摸 `getStringStore(0)` 那张原始表来验"值真的没掉"，绕开所有会顺手清理的读路。
  这一段值得留在文档里：多把尺互相兜底的代价不是重复劳动，是**兜底之间互相遮掩** ——
  坏掉的那一把没人能发现。
- 还没做的：① `expireDb` / `pexpireDb` / `persistDb` / `ttlDb` / `pttlDb` 五支仍然只看
  `stringStores`，fence 原样在 `RedisServerProtocolSemanticsTest:4141-4156`（钉的是我们
  在流键和 list 键上都回 `:0`、上游回 `:1`），翻它是下一格；② `removeAnyType` 里那句
  `clearExpireAtDb` 对集合键还是保险 —— 今天构造不出"集合键 + 时刻行"，翻完 fence 它才是真腿；
  ③ dump 那一族只有 String 节写过期栏，四个集合节压根没有这一栏、载入端把 `expireAt` 整个丢掉，
  补它要把 `ZCHRDB` 的 `RDB_VERSION`（`RdbPersistence.java:65`、`:70`，载入端 `:569` 版本不符直接拒读）
  抬到 2 并先定旧档怎么办。

#### 枚举那四家合成一把尺 `liveKeys`；而 `INFO keyspace` 一直是第五把，它连流键都没数

- `MemoryStore.liveKeys(db)` 新增：先把六张表的键名**抄**进一个 `LinkedHashSet`（顺序仍是
  string→hash→list→set→zset→stream，所以 `KEYS` 那串的条序一个字没动），再逐个问 `typeOfDb`
  那一把尺。两阶段是故意的 —— 反过来"一边遍历某张表一边 `remove`"就要吃"各 store 交回的是不是
  快照"这个未知数，现在不吃。`keysDb` / `scan` / `dbsizeDb` 三家改为共用它。
- `CommandHandler.handleKeys` 不再自己并表。以前它拿 `keysDb` 的结果当底，再把 hash / list / set /
  zset 四张表**原样** `addAll` 回去（`LinkedHashSet` 只去重、不筛过期），那四句绕过了判活那道闸；
  模式匹配也在协议层又做了一遍。
- **`INFO keyspace` 是这一格里唯一"今天就数得错"的那一个**：它把五张表各自 `size()` 相加，
  既不算流表、也不判过期。上一格把流键接成"键是键"时接了四把尺，没接这一把，于是六枚键（含一枚
  `XADD` 出来的流键）在 `DBSIZE` 里是 6、在 `INFO` 里是 5，谁都不报错。现在它问
  `store.dbsizeDb(i)`。
- 权威，以及一处**有意跟上游不一致**：上游 `keysCommand` 遍历的是原始 `dict`，但逐键问一句
  `keyIsExpired`（`db.c:552`）；`dbsizeCommand` 就是 `dictSize(c->db->dict)`（`db.c:808-810`），
  INFO 的 `keys=` 同样是原始计数（`server.c:3681`）—— 即上游允许把"到点还没被碰"的键**暂时**数进去，
  因为 `serverCron` 推着 `activeExpireCycle`（`expire.c:97`）在扫。我们**没有**那个扫刷
  （实测：`z-cache-core/src/main` 里唯一的定时清刷是 `ZCache.cleanupExpired`（`ZCache.java:363`），
  那是嵌入式客户端缓存自己的 `ConcurrentHashMap`，服务器侧的 `MemoryStore` 一处都没有），
  所以照抄"原始计数"在不是暂时错，是**一直错**。这一格选的是一致性优先：四把尺永远同一个数。
- 代价也写清楚，别让它藏在"重构"两个字里：`DBSIZE` 从"每库几个计数"变成一次 O(该库键数) 的遍历
  （改之前它至少已经在 `stringStores` 上是 O(该表键数)，**量级没升、常数升了**），
  `INFO keyspace` 从 O(库数) 变成 O(总键数)，而上游这两个都是 O(1)。今天没有性能门禁钉这个数。
  真要抬回去，出路是补后台扫刷（下面"还没做的"①），不是把尺拆回去。
- 全量 `895 → 897`（`358 + 402 + 135 + 2`，`logs/keyspace_full.log`，判据字节就是上一条那两个 md5）。
  对外的行为差只有两处：`INFO keyspace` 那个数（流键从数不到变成数得到，N3 钉的就是它），
  以及 `KEYS` / `SCAN` / `DBSIZE` 三者对"同一枚键名落在几张表里"的口径统一成一枚。
- 判据与牙（`~/.cache/zcache_gauges/ttl_mut/keyspace_teeth.py`；基线在同一份字节上绿，
  两个被改文件各记各的 md5，每支还原后对账 `match=True`；被量字节
  `MemoryStore.java 018e4e84e0d1262079c407b313e9c2b0`、`CommandHandler.java 96a31e63ef13b0bec1db3d57bb55716d`）：
  - N1 `liveKeys` 摘掉判活那一句 → 3 红，含
    `MemoryStoreTest.theThreeEnumerationsAgreeOnEveryShapeOfKeySet:695 DBSIZE 这一问自己就要把到点的键摘掉 ==> expected: <2> but was: <3>`；
  - N2 `dbsizeDb` 退回"六张表各自加总" → 3 红，含
    `:687 一枚键名只数一个，不论它落在几张表里 ==> expected: <2> but was: <3>`
    （并存键名那一问和 `testDbsizeIgnoresExpired:444` 同时红）；
  - N3 `INFO` 退回"五张表各自 size() 相加" → 1 红，
    `RedisServerProtocolSemanticsTest.streamsAreOrdinaryKeysForKeyspaceCommands:4015 INFO 报的键数必须与 DBSIZE 是同一个数（少的那一枚是流键）... expected: <6> but was: <5>`。
    这一支的判据**必须加在网线层**：单测层那三个裸 `new CommandHandler(store)` 用的是进程级 static
    的 stream store 兜底，`MemoryStore.streamKeys(db)` 根本看不见它 —— 钉不到这一层就等于没钉。
  - N4（`handleKeys` 退回"再原样并四张集合表"）**那一轮整套 core 402 例全绿，当时我把它记成了"预期而不是漏网"**：
    理由是"集合键还拿不到时刻行（`expireDb` 那五支只看 `stringStores`），加回来的与 `keysDb` 交的逐字相同"。
    下一格（13b-ii）翻完 fence 按承诺把这一支重跑了一遍，**它红了** —— 那句"等价变异"作废，
    原因见下一节 N4 那一条：这一支丢的是 glob，而那一刻盘上已经有了还活着的 `e:*` 集合键能让它现形。
    教训写在这里：一次绿只说明"**这份字节上现有的判据**看不见它"，不说明它不可观测。
- 顺手验过两件事，都没靠推断：`CommandHandler.globToRegex` 与 `MemoryStore.globToRegex` 把空白和
  变量名抹掉后**逐字符相同**，所以"模式匹配挪进 `keysDb`"没有偷换 glob 语义；
  `handleRandomkey`（`CommandHandler.java:1299-1305`）本来就只问 `keysDb` 一把尺，这一格之后它抽的
  是判过活的键集，权重仍然均等。
- 对岸那一侧这一格没有实测：250 现在拒绝 ssh（`kex_exchange_identification` 直接 reset），
  `_doc/battery*.txt` 那批原文真值因此无法复跑，本格的权威只有 5.0.14 源码行号。
- 还没做的：①②③ 与上一格同样三条（翻 fence、集合键的时刻行、快照里的过期栏），另加
  ④ 服务器侧没有 active expire cycle ⇒ 到点又没人碰的键既不释放内存也不从时刻表里退，
  `liveKeys` 的顺带回收只覆盖"有人来枚举"的情况；⑤ `INFO keyspace` 这一行仍然只有 `keys=`，
  上游是 `keys=,expires=,avg_ttl=`（`server.c:3686`），补它要先定义得清 `avg_ttl` 在我们这里是什么。

#### 五种集合键第一次挂得上过期：EXPIRE 一族去掉类型分支，而"键空了自己掉"必须先接进时刻表

- **现象**（改动前，按网线原文记）：`EXPIRE` / `PEXPIRE` / `EXPIREAT` / `PEXPIREAT` 对
  hash / list / set / zset / stream 一律回 `:0`，`TTL` / `PTTL` 对它们回 `:-2`（"键不存在"），
  `PERSIST` 回 `:0` —— 只有 String 挂得上过期。这就是上一格留下的"还没做的 ①②"两栏。
- **归因**：时刻表上一格刚立起来，本来就按库、按键名存，五种集合键挂不上**不是忘了接线**，而是
  `expireDb` / `pexpireDb` / `ttlDb` / `pttlDb` / `persistDb` 每一支各自先去问一张只装 String 的表
  （`stringStores[db].get(key)`），问不出值对象就当场回"键不在"。五支各问各的，所以"谁能挂过期"
  这件事在命令层有**五把**尺。上游 `expireGenericCommand`（`expire.c:415-451`）通篇没有类型分支，
  `:426` 那一问只有 `lookupKeyWrite` —— 判"键在不在"与判"是哪一型"是同一把尺，我们这里就是
  `typeOfDb`，五支现在全部改从它走（收进一个 `armExpiry`）。
- **接上之前必须先补的一环**：集合键一旦挂得上过期，"值空了所以键不在了"那 **21 处**
  （`LPOP` 弹出最后一个元素、`SPOP` 掏空、`ZREM` 清完、`LTRIM` 裁空、`HDEL` 删掉最后一个字段……
  长在四个 store 各自内部，Hash 2 / List 8 / Set 4 / SortedSet 7）就把时刻行留在原地。
  后果不是"多一行垃圾"：留在原地的是一个**未来**时刻，同名键被重新写入之后直接继承它 ——
  上游那里 `TTL` 回 -1，这里会回一个看着完全合理的正数，谁也发现不了。上游删键只有一个口
  （`dbSyncDelete` 先删 `db->expires` 再删 `db->dict`，`db.c:271-281`），所以这里也做成一个口：
  四个 store 的整键删除收进各自的 `dropKey`，**真摘掉过**才通告，`MemoryStore` 在构造末尾把通告
  接到 `clearExpireAtDb`。stream 刻意不在这份名单里：`xdelCommand`（`t_stream.c:2413-2436`）
  掏空一条流并不删键，它的删除只从 `removeAnyType` 那一个口进来。
- `removed != null` 那一判是必需的，不是防御性写法：`removeAnyType` 会拿每个键名挨个问六张表，
  无条件通告等于"A 表什么都没摘掉，却把 B 表那一行的过期取消了"（P6 实测把它判成 27 条红）。
- 顺带两处同源缺陷：① `PERSIST` 不再把值原样 `putDb` 回去 —— 那里面带着 `clearOtherTypes`，
  于是"取消一枚 String 的过期"会顺手毁掉同名的另一型；网线层碰不到（命令层不允许一个键名同时挂
  两型），但 `RedisServer.getStore()` 交出去的就是 `MemoryStore`，嵌入式用户摸的正是两张表。
  ② `MOVE` 原先只有 String 腿带着时刻走，"集合类型没有 TTL 可搬"这句话在接线之前是真的、接线之后
  就成了漏；现在六型共用一个收尾，且**取时刻排在搬运之前**（上游 `moveCommand` 同序：`db.c:957`
  取 `getExpire`、`:965` 挂到目标库、`:969` 才 `dbDelete(src)`；这里两步先后与上游相反，因为
  `dbDelete` 要 decrRefCount 而我们的时刻表按库分栏，可观测结果一致）。
- 判据为什么写成"一张表一次断言"：JUnit 见到第一条红就抛，同一方法里第 2—N 条判据全被第一条遮住。
  六枚键各搬一次如果写六条 `assertEquals`，摘掉"搬到目标库之后重新挂上"那一尾巴只红在 string 那一行，
  另外五格一起丢了没人说 —— 判据当场退化成"只钉住 String"。收进一张 `Map` 整体比对之后，三支互斥
  变异各自点名漏了哪几型（见下面 Q1 / Q2 / Q3 的三条 `but was`）。
- 判据与牙（三支量具：`sixttl_teeth.py` 的 P1—P6 打"六型都能挂过期"这一段，`move_teeth.py` 的 Q1—Q4 打
  搬库那一条腿，`keyspace_teeth.py` 的 N1—N5 打"到点的键由谁收走"那一条；三支各自先在同一份字节上要求
  基线绿，每支还原后按 md5 对账 `match=True`。**下面每一条红的行号都出自 08:48—08:57 那一轮链**
  （`~/.cache/zcache_gauges/logs/chain_13bii_0848.out`，19 份日志 08:48:34—08:56:56 各 320—530KB），
  被量字节 `MemoryStore.java 8121ae2af90bc9796b5966c092e2a0b7`、`CommandHandler.java 96a31e63ef13b0bec1db3d57bb55716d`、
  `RedisServerProtocolSemanticsTest.java 9626dafbe63ad501a796fdbd7ac5a6c1`、
  `MemoryStoreTest.java 33dd9b07262122b7a144c911e4fcc4b8`、`ListStore.java fc8d1550cce974db20e08beda2415d43`、
  `SortedSetStore.java a14a4e6162f58eab04f6931b818e2c5d`）：
  - P1 `armExpiry` 摘掉"键在不在"那一问 → 6 红，含
    `streamsAreOrdinaryKeysForKeyspaceCommands:4207 前提：键不在时 EXPIRE 不许顺手造出一枚键 ==> expected: <:0> but was: <:1>`；
  - P2 整段不接时刻行通告口 → 1 红，正是
    `:4241->expiryRowDiesWhenTheKeyEmptiesItself:4305 e:list 复活之后不许继承上一枚键的时刻行 —— 上游那里键没了时刻跟着一起没 ==> expected: <:-1> but was: <:100>`；
  - P3 `ttlDb` 退回只看 `stringStores` → 2 红：`streamsAreOrdinaryKeysForKeyspaceCommands:4212 hash 键在而没挂过期：-1，不是 -2 ==> expected: <:-1> but was: <:-2>`
    与 `zstoreAndCrossDbMoveFollowTheMeasuredRows:2858 MOVE 之后集合键的 TTL 也要跟着过去: :-2`（那一格摘掉之后 `TTL` 又去问了 string 表）；
    ⚠ 下一格（13b-iii）之后同一支在树上量到的是 **3 红** —— 多出来那条是快照判据自己走上门的，见下一格最后一条；
  - P4 `persistDb` 退回只看 `stringStores` → 1 红
    `:4216 hash 挂上之后 PERSIST 读得到那一行 ==> expected: <:1> but was: <:0>`；
  - P5 只让 `ListStore` 不通告 → 1 红且**只**红在 `e:list` 那一行（`:4305`），逐 store 的归属对得上；
  - P6 `dropKey` 不看"真摘掉过"就通告 → **27 红**，所以它不是等价变异：`removeAnyType` 会拿每个键名挨个问
    每一张表，无条件通告等于"A 表什么都没摘掉，却把 B 表那一行取消了"，`SETRANGE 之后 TTL 要还在`
    （`:2351`）、`MOVE 之后 TTL 要跟着过去`（`:2833`）、`源键的 TTL 要跟着搬过来`（`:1897`）三条全被它带红；
  - Q1 摘掉"搬到目标库之后重新挂上"那一尾巴 → 3 红：整表断言交回
    `{mv:string=-1, mv:hash=-1, mv:list=-1, mv:set=-1, mv:zset=-1, mv:stream=-1}`，六格全丢，
    网线层 `zstoreAndCrossDbMoveFollowTheMeasuredRows:2833 MOVE 之后 TTL 要跟着过去: :-1`，
    老那一条 `MemoryStoreTest.moveAndRenameMoveTheRecordInsteadOfLeavingItBehind:350 搬库要带着原来的时刻`；
  - Q2 把"取时刻"那一步挪到搬运之后 → 2 红，整表交回
    `{mv:string=1790470672853, mv:hash=-1, mv:list=-1, mv:set=-1, mv:zset=-1, mv:stream=1790470672853}`：
    丢的正好是那四种"搬的过程中被自己的删除通告回收过"的类型，String 与 stream 反而对得上 ——
    注释里"取时刻排在搬运之前"那一句的牙就在这里；网线层配套红在
    `zstoreAndCrossDbMoveFollowTheMeasuredRows:2858 MOVE 之后集合键的 TTL 也要跟着过去: :-1`；
  - Q3 摘掉源库那一行的收尾 → 3 红：`MemoryStoreTest.everyTypeCarriesItsExpiryRowAcrossDbs:399
    六枚键全搬空之后，源库时刻表里不许留无主记录: [mv:stream, mv:string]`、
    `MemoryStoreTest.moveAndRenameMoveTheRecordInsteadOfLeavingItBehind:349 搬完以后源库不许还留着记录`、
    网线层 `:2887 搬走的流在源库里留下的时刻行不许被同名新键继承 ==> expected: <:-1> but was: <:200>`；
    它与 Q2 的红集互补（四种集合由通告兜住，剩下两型由 MOVE 自己兜）；
  - Q4 把 `PERSIST` 退回它本来的样子（摘完时刻再把值 `putDb` 写回一次）→ 1 红
    `MemoryStoreTest.persistRewritesTheExpiryRowAndNothingElse:421 PERSIST 不许顺手毁掉同名的 hash`。
- 枚举那三家（`DBSIZE` / `KEYS` / `INFO keyspace`）本来各自有一把尺，13b-i 之后都从 `liveKeys` 走，
  于是"到点的键由谁收走"这一维必须单独有牙来量（`keyspace_teeth.py`；N1—N4 是上一格那四支的**同一批**，
  本轮在同一份新字节上重跑，另加 N5。行号与上一节不同是因为网线层那一段判据变长了，两边各自抄的是自己那一轮的日志）：
  - N1 摘掉 `liveKeys` 里 `typeOfDb` 那道判活 → 4 红，其中**只有一条**钉住"没人碰过它、第一次问它是 KEYS"
    这一维：`RedisServerProtocolSemanticsTest.streamsAreOrdinaryKeysForKeyspaceCommands:4271
    到点之后没有一次读发生过：两枚集合键必须由 KEYS 自己收走 ==> expected: <[]> but was: <[px:hash, px:list]>`。
    同一跑里前面那条 `KEYS tt:*`（`:4255`）**没有红** —— 那两枚在更早的 `EXISTS`/`TYPE` 那一问就被
    读路径顺手回收了，所以那一问量的其实是读路径；这一段新判据中间一次读都不做，把"枚举自己负责回收"
    从"读顺手做了"里分了出来，这是它存在的全部理由；
  - N2 `dbsizeDb` 退回"六张表各自加总" → 3 红，含 `MemoryStoreTest.theThreeEnumerationsAgreeOnEveryShapeOfKeySet:751
    一枚键名只数一个，不论它落在几张表里 ==> expected: <2> but was: <3>`；
  - N3 `INFO` 退回"五张表各自 size() 相加" → 1 红：`:4051 INFO 报的键数必须与 DBSIZE 是同一个数（少的那一枚是流键）`；
  - N4 `handleKeys` 退回"把四张集合表原样并回来" → **红**（`reds=1`：`:4255 … but was: <[tt:string, tt:list, tt:set, tt:stream, e:hash, e:list, e:set, e:zset]>`）。
    **上一格在这里写的"今天是等价变异、绿着才是对的"是我编的**：这一支丢的是 glob，于是四枚还活着的
    `e:*` 被无条件并了回来，判据当场抓住。当时它报绿只因为那一份字节里还没有能红给它看的判据，
    我把"尺看不见"记成了"变异等价"；
  - N5 换成历史上真长那样的那一支（四张表各自带 glob 再并回来）→ 也红，但红在另一维：
    `:4042 expected: <[kt:string, kt:hash, kt:list, kt:set, kt:zset, kt:stream]> but was: <[kt:hash, kt:list, kt:set, kt:zset, kt:string, kt:stream]>`
    —— string 那几枚被排到了四张集合表之后。N4 红不蕴含 N5 红，反之亦然，所以这两维（并回来的是谁 /
    以什么顺序）各自都要有判据；这一支的红的不是过期，是**枚举顺序**，也照实记在这里。

- 一次**未归因**的红，照实记不写成"已排除"：Q4 那一跑里
  `RedisServerReferenceParityTest.debugSubcommandGrammarMatchesTheReference:937` 报了
  `0.3 秒必须真睡够，实测只等了 199ms`。它跟本支变异没有路径关系（PERSIST 不在 DEBUG 那条路上），
  单独把这一个类复跑 8 遍 8 绿、八份日志 `grep -c 只等了` 全 0；而"睡短了"朝负载噪声的方向也不该出现
  （客户端拿墙钟量的是下界，机器越忙只会读得更大），所以更像是墙钟被 slew 或回话错位一格，
  机制没定下来 ⇒ 已登记待办：那一条判据改用 `nanoTime`，好让下一次出现有意义。
  **这一条待办由下面那格（13c）关闭**：判据现在走 `System.nanoTime()`，红消息长成
  `实测只等了 155ms（nanoTime，不受墙钟影响）`（引用的是 13c 那一轮 T1 变异交回的那一行，
  行号也从 `:937` 挪到了 `:941`）。199ms 那一条读数仍按"未归因"记在这里，不追改。
  08:48—08:57 那一轮整链重跑（19 次 mvn，含同样的 Q4 那一跑 `reds=1`）**没有再出现**这一条，
  所以它仍是"未归因、本轮未复现"，不写成"已排除"。
  ⚠ 这 8 遍最初报的是"每遍 13 条失败"，那是**我的量具坏了**：命令漏了 `-am`，core 链接到 `~/.m2` 里
  旧的 z-cache-common，回话长成 `-ERR com/zifang/z/cache/common/protocol/RedisIntegerFormat`。
- 基线 `897 → 899`（`358 + 404 + 135 + 2`，全量 `mvn -o -B clean test` rc=0 / BUILD SUCCESS，量的就是上面
  那六个 md5）。新增两支都在 core：`MemoryStoreTest.everyTypeCarriesItsExpiryRowAcrossDbs`、
  `MemoryStoreTest.persistRewritesTheExpiryRowAndNothingElse`；网线层另加一段（`EXPIRE` 一族对六型各问一次
  ＋ 四枚"键空了自己掉"的复活判据 ＆ 集合/stream 搬库带时刻 ＆ 一枚"到点之后只被 `KEYS` 问过"的回收判据），
  没加 `@Test` 方法数不变。
- 250 那一侧这一格仍然没有实测：它现在拒绝 ssh（`kex_exchange_identification` 直接 reset），
  `_doc/battery*.txt` 那批原文真值复跑不了，本格的权威只有 5.0.14 源码行号。
- 还没做的：① 快照 —— 这一格之后缺口从"只有 String 带得进快照"变成"六型都挂得上过期，而四种集合的
  restore 腿一个字都不读那个参数"（`MemoryStoreAccessor.java:134 / :144 / :151 / :158` 收了 `expireAt`
  却没用，`:119-131` 那条才是完整的）。**这一栏下一格闭合**；那四个行号量的是写这一格时的字节，
  改动之后四腿长成 `:139 / :153 / :164 / :175`，而 `:119-131` 那条内联的闸也并进了共用的 `diedWhileOffline`。**格式不用动，这一句本轮查实了**：写侧四张集合表各自都取
  `expirationEntries.getOrDefault(key, -1L)`（`RdbPersistence.java:482 / :497 / :512 / :527`）并 `writeLong`
  进条目，读侧在 `:596` 无条件 `readLong` 再传给五个 `restore*` —— 也就是说**今天 SAVE 已经把集合键的时刻
  写进了文件，是 RESTORE 把它丢了**；上游同一件事也不分类型（`rdb.c:1188` 每个键 `getExpire` 一次、
  `:1015-1018` 在类型 opcode 之前写 `RDB_OPCODE_EXPIRETIME_MS`、`:2105` 加载时 `setExpire`、
  `:2097` 停机期间已过的键直接不 `dbAdd`），所以这是四行代码 + 一轮 SAVE→重启→读回的判据，不是破坏性抬版。
  ② 服务器侧没有 active expire cycle（不变，本轮 N1 那条红恰好说明"没人问就一直不回收"是真的）；
  ③ `INFO keyspace` 的行形状（不变）；④ 端口抢占：本轮见到的是**第三种形态**——前两格记的是"探针只 bind
  IPv4，看不见别人占在 IPv6 `*:25170` 上的 HTTP 应答"，而这次发现的危险是**方向相反**的：那种红如果落在
  变异那一跑上，会被量具读成"判据抓住了它"（`keyspace_teeth.py` 的 N1 与 `sixttl_teeth.py` 的 P6 只看
  `rc!=0`/有没有红）。本轮给两支量具加了 `run_guarded`：整跑红全是 RESP 签名→重跑，**混着抢占→直接
  SystemExit(7) 中止**，宁可停也不把抢占记成正向证据；四条分支（抢占→重跑→绿 / 混着→7 / 连三跑→7 /
  真红原样交回）都用假 `run_core` 实测过才上岗。`freePort()` 本身仍是待修（已登记）。

#### 集合键的时刻第一次活过一次重启：五支 restore 共用同两道闸，而时刻表上那一刀得整个拿掉

- **现象**（改动前，实测得到的形状，不是推断）：带 TTL 的 hash / list / set / zset 键 `SAVE` 之后重启，值
  完整地回来了，`TTL` 却报 `-1` —— 变成**永久键**；而停机期间到点的那一枚（`dead7`）整枚复活成永久键，
  `DBSIZE` 从 4 变 5。这一形状不是"我猜改动前长这样"：`prefix_shape.py` 用 `git show HEAD:` 把
  `MemoryStoreAccessor.java` 整份换回上一格的字节（`dacb87e1f4e429dbfda49021c32e2d32`，全程不 `checkout`、
  还原只从本次运行开头存的副本 `cp`、收尾按 md5 对账 `match=True`），只跑新加的那一条判据，交回
  `逐格: {l7=值 1 个成员, TTL -1, h7=值 1 个成员, TTL -1, s7=值 1 个成员, TTL -1, z7=值 1 个成员, TTL -1,
  dead7=EXISTS :1, DBSIZE=:5}`（`logs/prefix_shape.log`）。被换的那一份是 HEAD 的 accessor ＋ 当前
  `MemoryStore.java`（`8121ae2a…`，与 HEAD 逐字节相同 ⇒ 就是改动前那整棵树）。上游那里过期是**键**的属性：
  导出侧每个键问一次 `getExpire`（`rdb.c:1188`）、`RDB_OPCODE_EXPIRETIME_MS` 写在类型 opcode 之前
  （`:1015-1018`）、加载时 `setExpire`（`:2105`）、停机期间已到点的那一枚整键不 `dbAdd`（`:2097`）。
- **归因**：上一格"还没做的 ①"点中的就是这四条腿 —— 收了 `expireAt` 一个字都不读。**格式不用动**，
  本轮又按字节复对了一遍：写侧五张表各自 `expirationEntries.getOrDefault(key, -1L)` 再 `writeLong`
  （`RdbPersistence.java:467 / :482 / :497 / :512 / :527`），读侧 `:596` 无条件 `readLong` 传给五个
  `restore*` —— 时刻早就在文件里，是 RESTORE 把它丢了。
- **另一处同源缺陷，是这一格真正的雷**：`getAllExpirationEntries` 跟着 `getAllStringEntries` 也带了一道
  `isExpiredDb`，而**四种集合的值那半是从裸表枚举的**（与上游那一枚 `db->dict` 同形）。一边筛一边不筛的后果
  不是"少写一行时刻"：`dead7` 的值进了文件、时刻行没进，于是它带着 `expireAt = -1` 走到加载步，
  **变成永久键复活** —— 一份再也删不掉的数据。S4 就是把那道闸加回去，实测交回
  `{l7…z7 四格 TTL 3599（全好）, dead7=EXISTS :1, DBSIZE=:5}`：坏的那一维被四格好的那几维原样衬出来。
  现在时刻表整个不筛（`new HashMap<>(store.expirationSnapshot(db))`），决定统一放到加载那一步，与上游同位。
- **五支腿收成一个口**：`diedWhileOffline`（`:190`）与 `armAfterRestore`（`:195`）两个私有收尾，
  四腿在 `:139 / :153 / :164 / :175`；`restoreString` 原先自己内联写了一遍"剩余毫秒 <= 0 就 return"，
  现在也从同一个口走（`:117-126`），语义逐字不变（同一个条件、同一个次序）。挂时刻排在写完值之后，
  因为 `MemoryStore.armExpiry` 先问 `typeOfDb`（`MemoryStore.java:687-697`）。
- **判据为什么又是一张表**：JUnit 见到第一条红就抛。这一格第一版把"四格值 + TTL"与"`EXISTS dead7`"
  写成两条 `assert`，S6 那一跑只红在 TTL 那条，`EXISTS` 那一维整个没交回来，量具当场判成 RED-WRONG ——
  上一格为 `mv` 六格立过的规矩，这一格在同一张表上第二次付学费。收进一张 `LinkedHashMap` 之后每一次红
  都逐格全交，没坏的那几格就是归属的阳性对照（网线层 `RedisServerLifecycleTest:509`）。
- 判据与牙（第四支量具 `snapshot_teeth.py`；TRACKED = `MemoryStoreAccessor.java f82396f434bd9e58e926ee4fafe9234a`
  ＋ `RedisServerLifecycleTest.java 930c78084a5afaaaa47c61ef3986e0c4`，两支都在注入前存 `.good`、每支还原后
  `match=True`；下面每一条读数都出自 09:29—09:42 那一轮链 `logs/chain_13biii_0930.out`，
  S4 因换 hint 单独复跑过一次 `logs/snap_S4-…-again.log`，`SUMMARY mutants=6 bad=0`）：
  - S1 只摘掉 hash 的挂时刻 → 1 红，逐格 `h7=值 1 个成员, TTL -1`，另外三格 `TTL 3599` 一起交回；
  - S3 四种集合一起不挂时刻 → 同一格里四格全 `TTL -1`，`dead7=EXISTS :0`、`DBSIZE=:4` 仍然对；
  - S5 摘掉 zset 写值那一步 → `z7=值 0 个成员, TTL -2`、`DBSIZE=:3` —— 同一格里两维各自点名，
    不存在"先红的那批把后一批盖住"；
  - S4 把 `isExpiredDb` 加回时刻表 → `dead7=EXISTS :1`、`DBSIZE=:5`，而四格全好（见上面那一条）；
  - S6 加载侧的闸与挂时刻一起摘掉（＝改动前的结构）→ 交回的逐格与 `prefix_shape.py` 在 HEAD 字节上量到的
    那一行**逐字相同** —— 这一条同时是"新判据确实钉住了改动前那个形状"的正面证据；
  - S2 只摘掉加载侧那道闸 → **MEASURED-GREEN**（`reds=0`）。不许写成"等价变异，可以删"：绿只说明
    *现有判据*看不见它。它的绿由另外两支定位，不是我自己推的：S6（把挂时刻一起摘掉就红在 `dead7=EXISTS :1`）
    说明 S2 之下 `dead7` 确实是"先写进来、再被 `armExpiry` 的 `relativeMillis <= 0` 那一条腿当场删掉"
    （`MemoryStore.java:670-672 → :687-697`）；String 那一腿走的是另一条路 —— `psetexDb` 不经 `armExpiry`
    （`:380-385`），写进去的是带过去绝对时刻的行，由 `GET` / `DBSIZE` 那一问把它摘走，所以同一跑里
    `:435 gone → $-1` 与 `DBSIZE :2` 两条都还绿，而"惰性回收那把尺是不是活的"另有正面证据：同一轮
    `keyspace_teeth.py` 的 N1 摘掉判活那一问会红 4 条。⇒ 这道闸删掉的是"谁来做这件事"，不是"做不做"；
    留着它的理由是它把决定放在与上游同一个位置（`rdb.c:2097`），并且省掉一次写了再删。
  - hint 的一条自证：S4 的第三条 hint 现在写作 `l7=值 1 个成员, TTL 359`（3599 的前缀）。原本写的是
    `TTL 3`，我怀疑它是"顺手匹配上 3599"的假牙，就拿同轮那三份日志双向量了一遍：`TTL 3` 在 S4 命中、
    在 S3 与 S6 各 0 命中 ⇒ 它本来就分得开"四格是好的"与"四格变永久键"，**没有假牙**，我差点把一件没坏的
    事当 bug 改掉；换成 `TTL 359` 只是为了让读的人一眼看出那是三千多秒而不是三秒，换完按新 hint 复跑 S4
    仍然 TEETH-OK。
- 上一格那 15 支（N1—N5 / P1—P6 / Q1—Q4）本轮在同一份新字节上整链重跑（同一轮链的 [3][4][5] 段）：
  **15 支里 13 支的红集逐字相同**（连行号与 `but was` 的取值都一样；只有 `mv:*` 那两处的 epoch 字面值随轮次变）。
  差的两处：一处是 **P3 从 2 红变 3 红**，多出来的那条正是本格新加的判据：
  `RedisServerLifecycleTest.saveSnapshotsEveryDatabaseAndTheirTtls:509 … {l7=值 1 个成员, TTL -2, h7=值 1 个成员,
  TTL -2, s7=值 1 个成员, TTL -2, z7=值 1 个成员, TTL -2, dead7=EXISTS :0, DBSIZE=:4}` ——
  `ttlDb` 退回只看 String 表时，重启回来的四种集合键在 `TTL` 那一问眼里**根本不存在**（`-2` 而不是 `-1`）。
  它不在本格的变异家族里，是新判据自己走上门的，所以顺手把上一格 P3 那条的读数标成了"2 红（下一格之后 3 红）"。
  另一处是 P6 那 27 条红里 `RedisServerLifecycleTest` 那条的行号从 `:412` 挪到 `:435`（前置条件那一段变长了），
  两边各自抄的是自己那一轮的日志。
  **比法记在这里以免被当成眼力活**：三支量具每支的 `*.log` 文件名固定，后一轮把前一轮覆盖了，所以只能拿
  两份链文件（`chain_13bii_0848.out` / `chain_13biii_0930.out`，它们内嵌了每支交回的红行）按支取差集，
  归一化只把绝对时刻 `17\d{11}` 换成 `<EPOCH>`，其余逐字比。
- 基线 `899` 不变（`358 + 404 + 135 + 2`，全量 `mvn -o -B clean test` rc=0 / BUILD SUCCESS）：本格的判据
  全长在既有的 `@Test` 方法里，网线层多六格（四种集合 + `dead7` + `DBSIZE`）、种子多五枚键，方法数没变。
- 250 那一侧仍然没有实测：它还是拒绝 ssh（`kex_exchange_identification` 直接 reset），
  `_doc/battery*.txt` 那批原文真值复跑不了，本格的权威只有 5.0.14 源码行号。
- 还没做的：① **stream 键压根进不了快照**（本轮实测）：`RdbPersistence.java` 里
  `TYPE_STREAM|getStreamStore|StreamStore` 命中 **0**（同一条命令里 `DataOutputStream` 命中 9 当阳性对照），
  `MemoryStoreAccessor.java` / `StoreAccessor.java` 里 `Stream` 命中 **0**（对照：`SetStore` 在前者命中 9、
  `getAllSetEntries` 在接口命中 1）；那张表长在 `com.zifang.z.cache.core.stream.StreamStore`，由
  `MemoryStore.bindStreams`（`MemoryStore.java:329`）从外面接进来，持久化层没有一支枚举它 ⇒
  `XADD k * f v; SAVE; 重启; EXISTS k` 回 `:0`。上游 `rdbSaveObjectType`（`rdb.c:625`）对 `OBJ_STREAM`
  直接给 `RDB_TYPE_STREAM_LISTPACKS`（`:655-656`，常量在 `rdb.h:93`）⇒ 流键本来就该进快照。
  补它要动格式（多一个类型字节）＋ 定编码，是抬版而不是四行代码，所以单立一格；
  ② 服务器侧没有 active expire cycle（不变，本格 S2 那条又给它添了一条旁证：字符串那半靠的是"没人问就不回收"）；
  ③ `INFO keyspace` 的行形状（不变）；④ AOF 单独那一辈：`EXPIRE` 一族五支都在 `WRITE_COMMANDS` 名单里
  （`CommandHandler.java:3308-3311`，本轮现读），所以重放会经过 13b-ii 之后那套类型无关的命令层；
  但"删掉 `dump.rdb`、只让 AOF 把带过期的集合键重放回来"这一格当时**还没有判据** —— **这一格已由下面的 13d 闭合**
  （`aofReplayCarriesAbsoluteExpiryAcrossDowntime`，`RedisServerLifecycleTest:541`，删 rdb 在 `:601`，
  五种类型各一枚 + SETEX + SET 带 EX 三个入口）。
  ⚠ 那句话里的行号是当时现读的（`:602 / :629 / :767`），13d 插进一条 `@Test`（+147 行）后移到
  `:749 / :776 / :914`（本轮 `grep -n 'deleteIfExists(dir.resolve("dump.rdb"))'` 现读）；
  顺带纠正同句里的一处归因：那三处并不都"量的是 SETBIT" —— 只有 `:914` 所在的 `bitWritesAreJournaledAndReplayed`
  是 SETBIT，`:749 / :776` 所在的 `aofReplayRestoresDataAcrossThreeGenerations` 量的是 SET/LPUSH/BLPOP 的跨三代重放。
  本格那条判据方向相反、在 `:451` 删的是 aof（未受位移影响）。
  ⑤ 端口抢占（不变，已登记）。

#### DEBUG SLEEP 那一问换成单调钟：一条归因不下来的红，先把"下一次出现有没有意义"做出来

- **来账**：上上一格留了一条**未归因**的红 —— `0.3 秒必须真睡够，实测只等了 199ms`，单独复跑那一个类
  8 遍 8 绿、八份日志 `grep -c 只等了` 全 0，机制定不到任何一条路径上。当时登记的待办就是这一格：
  那条判据拿 `System.currentTimeMillis()` 量 elapsed，于是墙钟在那 0.3 秒里被往回拨一下就能读出一个偏小的数
  —— **这条红从此不可能有意义**（出现了也分不清是睡短了还是钟动了）。
- **改动**（只动测试，生产代码一行没改）：`RedisServerReferenceParityTest:933-941` 的 elapsed 换成
  `System.nanoTime()`（除以 `1_000_000L` 取毫秒），红消息里明写"（nanoTime，不受墙钟影响）"。
  阈值 `>= 250` 不动 —— 那是 300ms 那一问的调度余量，与用哪个钟无关。
- 牙（第五支量具 `sleep_teeth.py`；基线先在同一份字节上绿、每支还原后 `match=True`；被量字节
  `CommandHandler.java 96a31e63ef13b0bec1db3d57bb55716d`（HEAD，未改）＋
  `RedisServerReferenceParityTest.java c7d8b2bc86a46ab180770305c9edb72f`，日志 `logs/sleep_*.log`）：
  - T1 把服务端 `seconds * 1000.0` 改成 `* 500.0`（只睡一半）→ 1 红，
    `debugSubcommandGrammarMatchesTheReference:907->run:1398->lambda$…:941 0.3 秒必须真睡够，
    实测只等了 155ms（nanoTime，不受墙钟影响）`；
  - T2 整条 `if (ms > 0) Thread.sleep(ms)` 摘掉（完全不睡）→ 同一行红，读数 `0ms`。
    两支读数各自与注入量成比例（155 ≈ 300 的一半、0 ＝ 不睡），也就是这条判据报出来的数就是它名字里那个量；
    `SUMMARY mutants=2 bad=0`。
- 顺手把同型扫了一遍（判据不是"我以为只有这一处"；命令是在 `z-cache/` 下
  `grep -rn "System.currentTimeMillis() - " --include='*.java' . | grep '/src/test/'`，命中三处、全在测试树）：
  `RedisServerLifecycleTest:117`（`assertTrue(System.currentTimeMillis() - begin < DEADLINE_MS, "stop() 不能阻塞等锁")`，
  `DEADLINE_MS = 5_000L` 定义在同一个类的 `:30`）、`ZCacheConnectionTest:68`（用它的断言在 `:69`，`elapsed < 5000`）、
  `ZCachePoolTest:432`（断言在 `:436`，`elapsed < 1000`）。**三处都是上界**，和本格那一问（下界 `>= 250`、
  而且真要有 300ms 的睡）方向相反：墙钟往回拨只让上界读得更小（顶多掩盖缺陷，不会凭空报红），要误报红得先有
  一次比整个余量（1—5 秒）还大的正向跳变 ⇒ 本格不动这三处。
  同一个类里剩下的两处 `currentTimeMillis()`（`RedisServerReferenceParityTest:1459-1460`）是
  **等待上限的 deadline**（`deadline = now + DEADLINE_MS` 再 `while (now < deadline)`），也不量时长。
  两条对照，防"没扫到"其实是尺看不见：测试树里 `System.currentTimeMillis()` 共 22 处命中（远不止那三处减法，
  说明这个符号在尺眼里不是隐身的），而 `nanoTime` 在测试树里只有本格新写的那两行（`:933` 取起点、`:936` 求差）。
- 基线 `899`（`358 + 404 + 135 + 2`，全量 `mvn -o -B clean test` rc=0 / BUILD SUCCESS / Total time 32.4s，
  日志 `logs/full_13c.log`，36 份 surefire XML 全部晚于本轮起点）：本格没有新增 `@Test`，方法数不变。
  ⚠ 顺带纠正一处我自己用错的常识：全量 reactor 在这台机器上是 **32 秒**级，不是我此前反复据此排程序的
  "5—6 分钟"级（那一估来自把 08:48 那轮链 9.4 分钟整个记到全量那一跑头上，实际那是 19 次 mvn 的总和）。
  以后"要不要跑全量"不该按 5 分钟的成本来权衡。

#### AOF 里记的是相对时间：重启会救活已经死掉的键，还给所有键免费续期（13d）
- 现象（改动前的形状是量出来的，不是推断）：把 `CommandHandler.java` 整份换回 HEAD 的字节
  （`git show HEAD:…` 重定向进文件，**不动** `checkout`；还原只从本次运行开头存的副本 `cp`，
  收尾 md5 `6ecc59190be6a2296a290e1bb7a2f045` 对账 `match=True`），只跑本格新加的那条判据
  （`logs/aof_ttl_headshape.log`，rc=1），逐格表交回：八格活着的 `TTL :3600`（真实停机 1568ms 一秒没扣）、
  `g_setex=EXISTS :1, TTL :1`、`g_hash=EXISTS :1, TTL :1` ⇒ 两枚"停机期间就该死掉"的键重放之后又活一个完整周期。
- 上游那一处在落盘<em>之前</em>就换写：`feedAppendOnlyFile`（`aof.c:594-606`）把 EXPIRE / PEXPIRE / EXPIREAT
  换成 PEXPIREAT，SETEX / PSETEX（`:598-606`）与带 EX/PX 的 SET（`:607-622`）拆成 SET + PEXPIREAT；
  换算体 `catAppendOnlyExpireAtCommand`（`:550-577`）里那句 `when += mstime()`（`:566`）就是
  "日志里不留相对时间"的地方，注释（`:543-549`）写得很直白：<i>the time is always absolute and not relative</i>。
  重写那一辈同理（`rewriteAppendOnlyFileRio` `:1299`，逐键在值之后补一条 `*3\r\n$9\r\nPEXPIREAT\r\n`，`:1352-1356`）。
- 改动：`CommandHandler.aofRecordsFor(args, result)`（`:3399`）＋ `writeAofRecords`（`:3465`，一条命令可以
  换写出多条，`SELECT` 前缀只打一次）。记录只有两种结局 —— **换成绝对时刻，或者一个字都不记**，
  不承认"退回原样记 argv"这第三种。时刻取自 `MemoryStore.expireAtDb`（命令刚执行完，表里那一行就是它
  真正挂上的值），不再重算 argv：重算要跟着 EXPIRE/EXPIREAT/溢出饱和的文法走第二遍，而那一行是命令层
  已经裁定的唯一结果。`result` 是 `RespError` 就不记 —— 这一判是写完之后被自己的判据逼出来的：
  一条失败的 `SETEX k 0 bad` 之后 k 身上那行时刻是<em>上一次</em>成功挂的（`> 0`），只看时刻就会把
  "从来没写进去的那个值"连旧时刻一起记进日志，重放之后 k 的内容被一条从来没成功过的命令改掉（M6 量的就是它）。
  `SET k v NX` 的 NX/XX 原样保留、只摘 EX/PX 那一枚连同它的数值（`setWithoutExpireOption` `:3441`），
  因为我们不像上游那样"只记真改过库的命令"，削成三条参数会把 NX 那一判在重放时演丢。
- 判据 `RedisServerLifecycleTest.aofReplayCarriesAbsoluteExpiryAcrossDowntime`（`:541`）：AOF-only 重启
  （删掉 `dump.rdb`）＋ 真实停机 1.5 秒，十格收一张表（八格活的：值/内容 + `TTL ∈ (3000, 3600 − 停机秒数]`；
  两格死的：`EXISTS :0` / `TTL :-2`），行为断言在 `:647`。表里五种类型各一枚（str/list/hash/set/zset）
  —— 上一节 ④ 那条待办（"删掉 `dump.rdb`、只让 AOF 把带过期的集合键重放回来"从来没有判据）到此关闭。
  另加一层结构守卫（`:658` / `:666`）：直接 `loadAof` 读回每条记录的动词，必须有 `PEXPIREAT`、
  且不得出现任何相对时间的过期动词。它与行为层不重叠：M5、M7 两支行为十格全绿，只有这一层红。
- 牙（第七份量具 `~/.cache/zcache_gauges/aof_ttl_mut/aof_ttl_teeth.py`；被量字节
  `CommandHandler.java 6ecc59190be6a2296a290e1bb7a2f045`，每支还原后 md5 对账 `match=True`；
  日志 `logs/aof_ttl_*.log`，链文件 `logs/aof_ttl_chain_final.out`）：
  M1 整块换写摘掉 → 点名 `g_hash, g_setex`；M2 SETEX/PSETEX 那一支摘掉 → `a_setex`；
  M3 SET 带 EX/PX 那一支摘掉 → `a_setopt`；M4 只记时刻、把值那一半摘掉 → `a_setex`；
  M5 换写成 EXPIREAT（秒精度）→ 结构守卫；M6 摘掉"回的是错就不记"那一判 → `a_setbad=EXISTS :1, GET poison`；
  M7 挂不上时刻时退回原样记 argv → 结构守卫。`SUMMARY mutants=7 bad=0`。
  过程中修掉两处量具自己的毛病，记下来免得重犯：
  ① M4 第一版写成 `Arrays.asList(expireRecord)`（单元素数组被推成 `List<String>`）编译不过，
  `rc=1` 而红行 0 条 —— 这种"崩红"不是判据赢，脚本现在见 `COMPILATION ERROR` 直接 FATAL 退出；
  ② M6 的 hint 我凭形状写成 `a_setbad=GET poison`，真红行是 `a_setbad=EXISTS :1, GET poison, TTL :3599`，
  于是被自己判成 RED-WRONG；改成从断言消息复制之后 TEETH-OK。
- 基线 `900`（`358 + 405 + 135 + 2`，全量 `mvn -o -B clean test` rc=0 / BUILD SUCCESS / Total time 34.0s，
  日志 `logs/full_aof_ttl_final.log` 连同 `.rc` / `.tally`，36 份 surefire XML 全部晚于本轮起点）：
  core 404 → 405，本格新增 1 个 `@Test`。
- 顺手扫到的下一格（不在本格修）：`AofPersistence.rewriteAof()`（`:319`）写的是**空**命令集
  （注释自陈"实际实现需要依赖 StoreAccessor 来获取当前数据库状态"），然后把真 `appendonly.aof` 删掉并
  `truncateAndReopen()`。它今天零调用方（全仓 `grep -rn rewriteAof` 只有定义那一行），但方法是 `public`：
  谁把它接上 `BGREWRITEAOF` 或体积自动重写，谁就在抹整个数据集。要么按上游那一遍补全
  （逐键导出 + 每键一条绝对时刻 `PEXPIREAT`），要么连 `rewriting` / `rewriteExecutor` 一起删掉。
  **这一格由下面的 13e 关闭**（那一处的 `:319` 与下面的 `:3470 / :3473` 都是当时现读的行号，
  13e 之后分别移到 `AofPersistence:420` 与 `CommandHandler:3470`）。
  对照记录：`appendCommand` 的调用点只有两处（`CommandHandler:3470 / :3473`），都在 `writeAofRecords` 里，
  所以这一格的换写没有被绕过的第二条写入路径。
- 250 那一侧仍然没有实测：它还是拒绝 ssh（`kex_exchange_identification` 直接 reset），本轮全部读数单机。

#### AOF 重写导出的是空日志，接上它的人就是在抹数据集；顺手把"日志的落点"从连接身上搬回日志（13e）

- **来账**：上一格末尾登记的那条待办 —— `AofPersistence.rewriteAof()` 写一份**空**命令集再删掉真有内容的
  `appendonly.aof`。**这一格由本格关闭**。它当时零调用方，所以没坏过任何东西；坏东西的是"下一版接上它"：
  `BGREWRITEAOF` 一旦补上，或者按体积触发自动重写，第一条就是抹库。
- **改动**（`AofPersistence`）：`rewriteAof()`（`:420`）现在真的导出当前状态 ——
  `exportMinimalCommandSet()`（`:477`）逐库补 `SELECT j`（只给非空的库，上游 `aof.c:1306 / :1308`）、
  逐键按类型补 `SET` / `HMSET` / `RPUSH` / `SADD` / `ZADD`，每个键之后紧跟一条**绝对时刻**的
  `PEXPIREAT`（`:557`，上游 `aof.c:1352-1356`），变参命令按 `REWRITE_ITEMS_PER_CMD = 64`（`:146`，
  上游 `server.h:100`）分片（`:538`）。写出去走的是追加路径那一份 RESP 编码器（`writeRecords :573`），
  所以两侧的字节形状同源。落盘是"写临时文件 → 关旧句柄 → `renameTo` → 重开追加句柄"，全在
  `appendCommand` 用的那把锁里；`renameTo` 失败也先把句柄接回去再抛（留着 `writer == null` 会让之后
  每一条写命令在判空里静默丢掉，比不重写更糟）。没接 `StoreAccessor` 时**拒绝**：抛
  `IllegalStateException` 且一个字节都不写 —— 导不出状态就只能导出"空"，而空日志接下来会盖掉真的那一份。
  `RedisServer:222` 把那一份 `MemoryStoreAccessor` 提出来共用：快照在 `:223` 接，重写在 `:235` 接，
  两侧导的是同一份状态。
- **同时改掉的第二格（这一格是判据自己走上来的）**：`writeAofRecords()`（`CommandHandler:3470`）原来按
  "这条连接在不在 DB 0"决定补不补 `SELECT`。上游比的不是这个 —— 它比的是**日志当前的落点**：
  `if (dictid != server.aof_selected_db)`（`aof.c:586`）才补 `SELECT` 并更新（`:592`），字段声明在
  `server.h:1087`（"Currently selected DB in AOF"），换过一次日志之后抹回 `-1`
  （`aof.c:1771`，注释原文 "Make sure SELECT is re-issued"），启动与停机同理（`server.c:1602` /
  `aof.c:241`）。落在连接上有两个洞，都在本轮现形：
  ① 两条连接交替写时，一条切去 DB 3 写过之后，留在 DB 0 的那条补不出前缀 —— 它的每一次写重放时全部
  落进 DB 3，而在原库里读不见了；
  ② 重写之后日志整个换了一份，"我上一条写在哪个库"这个本地记忆与磁盘上的位置脱节。
  现在落点是日志自己的属性：`lastJournaledDb`（`AofPersistence:132`，初值 -1）+ `appendCommand(int, String[])`
  （`:335`），`start()`（`:221`）/ `stop()`（`:250`）/ 换完文件（`:461`）三处抹回 -1。
- 判据三支（全在 `RedisServerLifecycleTest`，全部走真实进程边界 + 真实文件）：
  `aofRewriteExportsTheDatasetInsteadOfEmptyingTheLog`（`:686`）—— 61 次同键流水 + 五种类型 + 130 成员的
  集合 + 含 CRLF 的值 + 一个已删的键 + DB 3 里的键，结构层八格（`:820`：塌成一条 / 分片 3 片共 130 个 /
  只给非空库补 SELECT / `PEXPIREAT` 恰两条 / 删掉的键不再提 / 单条至多 66 参数 / 不得留相对时间 / 体积必须降），
  行为层在删掉 `dump.rdb` 重启之后逐格读回 20 格（`:869`）。
  `aofPositionFollowsEachConnectionsOwnDatabase`（`:919`）—— 两条连接交替写，结构层（`:979`）按日志自己的
  SELECT 走一遍算出"每个键重放时会落在哪一库"，顺带钉"同库连写不重复打前缀"（前缀恰好三条）；
  行为层（`:1007`）只留 AOF 重启后逐格问它在不在。
  `rewriteWithoutStoreAccessorRefusesInsteadOfWipingTheLog`（`:879`）—— 拒绝式判据 + 断言日志的字节一个都不动。
- 改前的形状（在 `git archive HEAD`（`3da16d8`）解出来的独立树上跑本轮这三份判据，不碰工作区，
  日志 `logs/rw_headshape.log`）：`Tests run: 3, Failures: 3`；重写那一跑报
  `重写前记录数=207 → 重写后记录数=0`、`字节=0（重写前 7344）`（整个数据集没了）；
  落点那一跑报 `SELECT 序列=[3, 3]`、`b_in0 / c_in0 实际日志把它放在 3`；
  拒绝那一跑报 `Expected java.lang.IllegalStateException to be thrown, but nothing was thrown.`。
- 牙（第八份量具 `~/.cache/zcache_gauges/aof_rw_mut/aof_rw_teeth.py`；被量字节
  `AofPersistence.java f8f40cecff9d9cf37d7ab7a3cf722dd3` + `CommandHandler.java 5e81c49fe782d4708e7bfd3896b8788c`，
  每支还原后 md5 对账 `match=True`；日志 `logs/aof_rw_*.log`）：
  W1 导出摘掉 → `塌成一条`；W2 逐库 SELECT 摘掉 → `逐库 SELECT`；W3 分片上限抬到无穷 → `分片, 单条上限`
  （读数 `1 片 / 130 个成员`、`最长 132`）；W4 空库不再跳过 → `逐库 SELECT`（读数 16 个库全在）；
  W5 导出不带时刻 → `时刻记录`；W6 时刻换成相对 `EXPIRE` → `绝对时刻`；W7 拒绝改成静默返回 →
  `IllegalStateException to be thrown`；P1 前缀回到"看连接在不在 DB 0" → `b_in0=重放时该落在 DB 0` + `GET r_after`
  （两支判据各自红，正好说明两个洞是同一条判据的两半）；P2 每条记录都补前缀 → `SELECT 序列`；
  P3 永不补前缀 → `SELECT 序列`；P4 换完日志不抹回 -1 → `GET r_after`。`SUMMARY mutants=11 bad=0`。
  过程中三处量具/判据自己的毛病，记下来免得重犯：
  ① P4 第一版报 **SURVIVED** —— 那不是等价变异，是**判据没有猎物**：重写前最后一次落盘在 DB 3、重写后
  第一条也写在 DB 3，陈标志碰巧补出了对的前缀。补一笔 `SET r_tail`（`:741`）把"重写前最后一次落盘"挪到
  DB 0，P4 才红在 `GET r_after`。**"标志复位"这一类判据，猎物必须是"标志的值恰好与复位之后一样"的那种排布。**
  ② `joined = "\n".join(reds)` 是按物理行拼证据，而判据里那条 `GET r_crlf` 的值自带 CRLF，一条断言在日志里
  会劈成好几个物理行 ⇒ P1 被误判成 RED-WRONG。现在 expects 只在 surefire 的 `Failures:` 那一段里找
  （`red_text()`），既跨得住劈行，又不会把 INFO 级日志里的 AOF 流水算进证据。
  ③ 冒烟（`control.py`：把判据里一格的期望值改成 9，量具必须报 GAUGE-HAS-TEETH）第一版报 **GAUGE-BLIND** ——
  尺是好的，hint 是我凭测试源码模板写的（"该落在 DB 0"），注入后的真红行是"该落在 DB 9，实际…0"。
  从实测日志逐字抄之后 `注入红=1 / GAUGE-HAS-TEETH`，还原 `match=True`。
- 未覆盖面（记账，不当分）：`renameTo` 失败那一支（重开句柄后抛 `IOException`）没有猎物 —— 本机没有可移植
  的办法让同目录改名失败；`rewriting` 的并发标志（"rewrite already in progress"）同样零判据。
- 与上游的差别（记账）：① 不 fork，导出与换文件全在写锁里，代价是重写期间写侧被堵住，换来的是不需要
  `aofRewriteBuffer`；② 键的导出顺序按类型分五趟，上游是一库里逐键问类型（`aof.c:1314` 那个
  `while((de = dictNext(di)) != NULL)`），**记录集合一致、只有先后不同**，所以两侧判据都不许钉键的先后；
  ③ Stream 键导不出来（`StoreAccessor` 没有 stream 的枚举口：同文件里 `getAll` 命中 6 处、`Stream` 命中 0 处），
  也就是说**一份含 stream 键的库过一遍重写，那一族键会从日志里消失** —— 与 #20（stream 进不了 RDB 快照）
  同根，登记为下一格。
- 仍然没接上的（记账）：`BGREWRITEAOF` 这个命令在 `z-cache-core/src/main` 里 0 命中（同尺阳性对照：
  `BGSAVE` 3 命中、`FLUSHALL` 在 `CommandHandler` 里接线），所以本轮修好的一版重写至今只在测试里跑；
  `rewriteExecutor`（`:114 / :157 / :172`）除了 shutdown 没有任何调度方；`syncFile()` 的注释自陈
  "这里通过 flush 保证数据写入操作系统缓冲区" —— 也就是 `FSYNC_ALWAYS` 与每秒 fsync 都**没有真的 fsync**，
  宣传的持久化强度还没兑现（新格）。**这一格由下一格（13f）关闭。**
- 基线 `903`（`358 + 408 + 135 + 2`，全量 `mvn -o -B clean test` rc=0 / BUILD SUCCESS / Total time 34.2s，
  日志 `logs/rw_full2.log`）：core 405 → 408，本格新增 3 个 `@Test`。
- 250 那一侧仍然没有实测：本轮全部读数单机（判据、牙、基线都在这一台机器上）。

#### `appendfsync` 三档第一次真的有差别：那一支从前只 flush，把"交给内核"宣传成了"交给介质"（13f）

- **来账**：上一格末尾登记的那条"新格" —— `syncFile()` 里只有一句 `writer.flush()`，注释自陈
  "实际的 fsync 需要使用 FileChannel 或 FileDescriptor"。**这一格由本格关闭**。后果是三档等价：
  `always` 那条广告（"每个命令同步写入，最安全"）从来没兑现过，`always` 与 `no` 掉电之后丢的一样多。
- **改动前的形状**（在 `git archive HEAD`（`950342e`）解出来的独立树 `~/.cache/zcache_gauges/fsync_head`
  上跑本轮的结构判据，不碰工作区，日志 `logs/fsync_headshape.log`）：`Tests run: 1, Failures: 1`，
  表里点名 `真的 fsync=syncFile() 里没有 getFD().sync()`、`记账的顺序=… 实际 sync@-1 计数@-1`。
  同一张表里另有四格（`openAppending 这一支还在` / `closeLiveWriter 这一支还在` / `两只手成对打开` /
  `两只手成对抹掉`）**不算改前的缺陷** —— 那两个方法是本格才引入的形状，HEAD 里没有它们。
- **改动**（`AofPersistence`）：日志自己记下"写了多少、已经要到盘上的是多少"，`syncFile()`（`:866`）
  从"只 flush"变成 flush → `stream.getFD().sync()` → 自增 `fsyncCount`（`:129`）→
  `fsyncedBytes = appendedBytes`（`:874`，上游 `aof.c:506` 同形：先 fsync、成功回来才记账）。三档各自的路径：
  ① `always` 在每条记录落地后当场刷（`:409`，上游 `aof.c:499-503` 就在 append 之后直接 `redis_fsync`）；
  ② `everysec` 写侧一笔都不刷，攒着由每秒那一拍刷，而那一拍的判据是 `appendedBytes != fsyncedBytes`
  （`:834`）—— 上游那一对偏移量是 `aof_fsync_offset != aof_current_size`（`aof.c:349`），而 `:343-347`
  那段注释专门写的是"缓冲区已经空了、但还欠着一截，这一拍仍要刷"，所以"没人写了"那一拍不许白刷、
  "还欠着"那一拍不许跳过；③ `no` 一次都不刷，但字节照样已经交出去了。另外两处是这一支的边角：
  运行中换档要跟着挂/撤那个定时器（`setFsyncPolicy :218` → `applyFsyncScheduler :892`，幂等；上游
  `config.c:493` 只改一个整数就够，因为它每轮事件循环重读那个整数，我们有线程就得自己跟上）；
  收摊时 flush → fsync → close（`stop() :286` → `closeLiveWriter(true) :305`，上游 `stopAppendOnly`
  `aof.c:236-238`，**连 `no` 档也要在放手前刷最后一次**）；换过一次日志之后给新 inode 刷一次并对齐偏移
  （`:515-519`，上游 `aof.c:1767-1774`）；启动接手现成日志时按文件长度起算、不欠盘（`:274`，
  上游重放完是对齐这两个量（`aof.c:865-867`，空日志同样 `:718`），起步则把 `aof_last_fsync` 对齐
  （`aof.c:285`））。`openAppending`（`:672`）同时接上 `writer` 与底下那支持有 fd 的
  `liveStream`（`:105`），`closeLiveWriter`（`:650`）在同一个 `finally` 里把两只手一起松开。
- 判据两把（`RedisServerLifecycleTest`，都走真实进程边界 + 真实文件）：
  `fsyncPolicyDrivesTheRealFsyncCadence`（`:1243`）十格收进一张表 —— `always 每条记录一次`（3 笔 SET +
  首条写补的 `SELECT` = 4 条记录，实测 delta 4）/ `always 换日志后给新日志刷一次` /
  `everysec 攒着的由那一拍刷` / `everysec 空转那一拍` / `everysec 写侧不刷盘` /
  `everysec 换日志后给新日志刷一次` / `no 档的字节确实出了手`（这一格是"0 次"那两格的阳性对照，
  否则 0 是量具坏了而不是档位对了）/ `换档把每秒那一拍挂上` / `no 档不刷盘` / `收摊前那一刷`。
  `syncFileAsksTheOperatingSystemAndNotJustTheHeap`（`:1434`）是行为尺结构上量不到的那一层：
  直接读 `src/main` 的字节，钉"真的 fsync"（`getFD().sync()` 在不在）、"记账的顺序"（自增必须在 sync
  之后）、"两只手成对打开"、"两只手成对抹掉"。
- 牙（第九份量具 `~/.cache/zcache_gauges/fsync_mut/fsync_teeth.py`；被量字节
  `AofPersistence.java cde27cdbf5bb262caa57f8cbf0200bc9` + 判据文件 `3e0ac79b0b928fe5ea2b51329e050c6d`，
  两份都由本次运行自己存 `.good`、每支还原后按 md5 对账 `match=True`；日志 `logs/fsync_*.log`）：
  M1 摘掉 sync 只留自增 → 结构红（`真的 fsync` + `记账的顺序`）而**行为尺保持绿**；M2 自增挪到 sync 之前
  → 结构红（`记账的顺序`），行为绿；M3 `openAppending` 只接 writer → 两把尺各自红（`always 每条记录一次`
  + `两只手成对打开`）；M4 `closeLiveWriter` 只抹 writer → 结构红，行为绿；M5 那一拍不看"欠着多少" →
  `everysec 空转那一拍`；M6 换档不重挂定时器 → `换档把每秒那一拍挂上`；M7 EVERYSEC 也逐笔刷 →
  `everysec 写侧不刷盘`；M8 收摊不 fsync → `收摊前那一刷`；M9 换日志那一支的档位判据反过来 →
  两档各自的"换日志后给新日志刷一次"；M10 `no` 档也逐笔刷 → `no 档不刷盘`。`SUMMARY mutants=10 bad=0`。
  M1/M2/M4 与 M5–M10 是**互补**的两层，这一点是跑出来的不是说出来的：前三支正是"计数照走、介质没被问过"
  那种形状，行为尺全绿；后七支结构上看不出错，只有节奏尺量得出。
- 过程中量具自己的一处毛病，记下来免得重犯：结构守卫第一版让 `methodBody()` 在找不到签名时当场
  `assertTrue` 抛掉，于是 `logs/fsync_headshape.log` 第一跑只剩一句"源码里找不到 openAppending"，
  把前两条**真有牙**的红吞得干干净净 —— JUnit 的 fail-fast 吞的是同一个方法里第 2..N 条判据。
  改成"四格连同'被钉的那几支方法还在不在'一起收进同一张表、一次断言"之后，改前那一跑才同时报出
  `真的 fsync` 与 `记账的顺序`。
- 与上游的差别（记账）：① **不 fork**。上游 EVERYSEC 那一刷走 `aof_background_fsync`（调用点 `aof.c:511`，
  函数本体 `aof.c:208` 把它交给 BIO 线程），我们的每秒一拍与 always 的逐笔刷都在调用线程上、且和 append 共用同一把锁 ——
  盘慢时会堵住写侧，这是换来"不需要 `aofRewriteBuffer` 那一整摊"的代价；② **JDK 没有 `fdatasync`**。
  `FileDescriptor.sync()`（Corretto 8 `java/io/FileDescriptor.java:131`）的 javadoc 明说返回时"data
  **and attributes**"都已落介质，而 `config.h:92-97` 在 Linux 上特意把 `redis_fsync` 定义成 `fdatasync`
  就是为了避开元数据 —— 我们这一侧比上游多刷一点元数据，方向是更安全，不是更弱；同一份源码里
  `fdatasync` 0 命中（同尺阳性对照：`sync` 在该文件 10 命中），也就是这一侧没有更省的那个选项；
  ③ 上游 5.0.14 不做目录 fsync（`fsyncFileDir` 在 `src/*.c` 里 0 命中，阳性对照 `redis_fsync` 在
  `aof.c` 里 4 命中），所以两侧都不防"新建的那个文件本身还没落盘"；④ 档位常量的**数值**与上游不同
  （我们 `:70/:75/:80` 是 always=0 / everysec=1 / no=2，上游 `server.h:355-357` 是 NO=0 / ALWAYS=1 /
  EVERYSEC=2），只活在进程内部，不上任何线格式。
- 未覆盖面（记账，不当分）：**掉电真值本机量不了** —— 判据读的是"有没有向操作系统要过 fsync"这句话
  和它的节奏，介质层要 fs 崩溃才看得出；`SyncFailedException` 之后不自增那一支没有猎物（本机没有可移植
  的办法让 `fsync` 失败）；上游 EVERYSEC 还有一道 `unixtime > aof_last_fsync` 的"同一秒内不重复发起"闸
  （`aof.c:508-509`，`aofFsyncInProgress` 在 `aof.c:202`），我们靠"每秒一拍"的时间语义兜住，没有对等的那个整数。
- 宣传口径同步：README 特性表那一行与 `_doc/001_arch/01-module-structure.md` §2.3.1 那条 ⚠（"只做到
  `BufferedWriter.flush()`"）按本轮实测改写 —— 那两处是 `950342e` 才推上去的，本格把它们说的话作废了。
- 基线 `905`（`358 + 410 + 135 + 2`，全量 `mvn -o -B clean test` rc=0 / BUILD SUCCESS，日志
  `logs/fsync_fullreactor.log`）：core 408 → 410，本格新增 2 个 `@Test`。
- 250 那一侧仍然没有实测：本轮全部读数单机（判据、牙、基线都在这一台机器上）。

#### Stream 一族第一次进得了重写后的日志：导出侧最后一族看不见的键（13g）

- **来账**：13e 末尾登记的「③ Stream 既进不了快照也导不出」。**本格关的是导出的那一半**，快照那一半
  仍然开着。后果不是丢几个字节而是丢整族键：`exportMinimalCommandSet` 只枚举五张表（String / list /
  set / hash / zset），含 Stream 键的库过一遍 `rewriteAof`，那一族从日志里彻底消失，重启后 `EXISTS` 回 `:0`。
- **改动前的形状**（`git archive HEAD`（`aed92e3`）解出的独立树 `head_tree/`，只覆写本轮那份测试，
  工作区一个字不动；日志 `logs/prefix_judge_merged.log`）：`Tests run: 1, Failures: 1, Errors: 0`，
  一张表点名 16 格 —— 结构层 `逐条 XADD` / `空流那一手` / `表顶要补 XSETID` / `组要重建` /
  `时刻排在类型记录之后` / `逐库 SELECT` / `流键排在所属 SELECT 之后`，行为层 `XRANGE s_orders` /
  `TYPE s_orders` / `TTL s_orders` / `表顶活过重写` / `组重建之后还喂得动` / `EXISTS s_events` /
  `TYPE s_events` / `空流的表顶` / `XRANGE s_in3 在 3 库`。
- **改动**：接口开第六个枚举口 `StoreAccessor.getAllStreamEntries(int db)`（`StoreAccessor.java:97`），
  `MemoryStoreAccessor:110` 从新加的 `MemoryStore.streamStore()`（`storage/MemoryStore.java:334`）拿那份
  台份。**它与另外五个口同一把尺：不过滤已到期的键**，到期由读侧的惰性删除兜住
  （`getAllExpirationEntries` 也是这么量的）；在这里换掉判据，会让「到期未清」的流键在导出侧和 TTL 侧各说一套。
  `AofPersistence:582-591` 补上第六家，`addStreamRecords`（`:652`）照上游 `rewriteStreamObject`
  （`aof.c:1172-1266`）的四手写：① 一条记录一条 `XADD`、**带显式 ID**、arity = 3 + 2×字段数、
  不套 64 一批（`:1181-1197`）；② 空流那一手 `XADD key MAXLEN 0 <表顶> x y`（`:1198-1210`），
  它要同时立住「键在、表顶在、记录不在」这三件事；③ **无条件**补一条 `XSETID`（`:1212-1217`，
  注释原文 『in case of XDEL lastid』）—— 删空/裁空的流靠它把表顶带过去，有记录的流也照样补，
  因为它防的正是「最后一条被 XDEL 掉了」那种形状；④ 逐组 `XGROUP CREATE key <组名> <组自己的读数位置>`
  （`:1220-1233`）。`PEXPIREAT` 排在四手之后（同上游 `:1351-1356` 的顺序）。
- **判据** `RedisServerLifecycleTest.aofRewriteCarriesStreamKeysAcrossTheSwap`（`:894`）：一台真服务器上
  铺五种形状（带组带消费者的 `s_orders`、被 XDEL 掏空的 `s_events`、整个 DEL 掉的 `s_gone`、落在 3 库的
  `s_in3`、DB 0 里一支 `SET s_tail` 当跨库对照），`rewriteAof` 换日志后删掉 `dump.rdb` 重启，
  **只留 AOF 那一侧**把格子读回来。结构层与行为层合成一次断言 —— 第一版分两次断言，结构层当场抛掉，
  行为层那 9 格的证据全被 JUnit 的 fail-fast 吞了。否定式那一格配了阳性对照：`XREADGROUP … c3 >`
  在补 `XADD 4-2` **之前**先问一次（重建出的组若游标倒回开头，这一问会读到旧条目），之后同形再问一次读到 `4-2`。
- **牙**（第十份量具 `~/.cache/zcache_gauges/stream_rw_mut/teeth.py`；被量字节
  `AofPersistence.java 848269ee461d6aaa5369caecabcee8ea` + `MemoryStoreAccessor.java 495cf4e2f41d0c1fe600c6a027588b17`
  + 判据文件 `RedisServerLifecycleTest.java 57bd5a6ff274b95f1c4497b73a92502d`，三份由本次运行自己存 `.good`、
  每支还原后按 md5 对账）：M1 枚举口交回空表 → 改前那 16 格整份；M2 摘掉 `XSETID` → `表顶要补 XSETID`
  + `表顶活过重写`；M3 摘掉逐组循环 → `组要重建` + `组重建之后还喂得动`；M4 空流丢掉 `MAXLEN 0` →
  `空流那一手` + `XLEN s_events`（那一手把空表演成有记录的流）；M5 显式 ID 换成 `*` → 6 格（`显式 ID`、
  `XRANGE s_orders`、`XRANGE s_in3 在 3 库`，加上组的那三格 —— 三条 `XADD` 重放时按当前钟点重排表顶，
  组的读数位置跟着漂）；M6 `PEXPIREAT` 排到四手之前 → `时刻排在类型记录之后` + `TTL s_orders`
  （**所以那一格不只是排版**：重放到时刻记录时键还不存在，时刻整个丢掉）；M7 组的位置写成 `0-0` →
  只有 `组的读数位置` 那一格，靠的就是上面那句阳性对照。`CONTROL 未变异 → OK`，`SUMMARY mutants=8 bad=0`，
  七支全部「漏 无；多 无」。M5/M6 的预期集是第一次跑实测出来的而非预判 —— 首轮它们各多报了 3 格与 1 格。
- **与上游的差别（记账）**：`rewriteStreamObject` 在组之后还要替每个手上有未确认条目的消费者逐条
  `XCLAIM … TIME … RETRYCOUNT … JUSTID FORCE`（`aof.c:1235-1260`，函数体 `:1150-1167`）。
  **这一侧没有 `XCLAIM`**（`src/main` 里零处理），所以组的读数位置过得重写，**pending 表与消费者状态过不去**。
  这是导出口再开第八家也补不上的结构问题，不是排版能补的差别。
- **未覆盖面（记账，不当分）**：① `结构层 显式 ID` 与 `结构层 删掉的键` 两格在 M1（枚举口交回空表）下
  **空跑** —— 那条日志里一条 `XADD` 都没有，「每条都带显式 ID」自然成立，s_gone 也没被提及。
  前者的猎物由 M5 给出（换成 `*` 它当场红）；后者**七支变异里没有一支能让它红**（M1 是「整族看不见」，
  不是「看见了已删的」），所以它现在只是防回归的一格，不许当成牙记账。`EXISTS s_gone` 改前改后同为 `:0`，
  它钉的是「补进这一族之后别把删掉的键带回来」，也不承担那两格的猎物；② 只钉了「同一条流内部」的相对顺序，
  跨流之间的顺序没钉（上游也不保证，它按键空间迭代）；③ 重启后消费者名单（`XINFO CONSUMERS`）不在判据里，
  因为它的来源正是上面那句 `XCLAIM`。
- **宣传口径同步**：README 特性表持久化那一行与 `_doc/001_arch/01-module-structure.md` §2.3.1 那句
  「含 Stream 键的库过一遍重写会丢掉那一族键」按本轮实测改写；两处同时把仍开着的两格写明
  （触发命令仍一个都没有、Stream 仍进不了 RDB 快照）。
- 基线 `906`（`358 + 411 + 135 + 2`，全量 `mvn -o -B clean test` rc=0 / BUILD SUCCESS，日志
  `~/.cache/zcache_gauges/logs/full_13g_123455.log`）：core 410 → 411，本格新增 1 个 `@Test`。
- 250 那一侧仍然没有实测：本轮全部读数单机（判据、牙、基线都在这一台机器上）。

#### 上一格自己的第一号缺陷：表顶是按有符号写的，2^63 以上那一串重放时没人认（13g-2）

- **来账**：`91569e2`（上一格）里那条 `streamId(long ms, long seq)` 写的是 `ms + "-" + seq`。
  表顶那两段是 **uint64 的位模式**，Java 的 `long` 只是它的容器 —— 最高位置起来时拼接会写出
  负号开头的一串。上一格的判据全在个位数 ID 上排布（`1-1`…`4-2`），所以这一维整个没量到。
- **改动前的形状**（工作区只改测试、生产侧就是 `91569e2` 那份字节；日志
  `logs/wide_prefix_124325.log`）：`Tests run: 1, Failures: 1`，红 3 格 ——
  `结构层 表顶的无符号写法`（导出的实录是 `XSETID s_wide -9223372036854775808-9`）、
  `高位的表顶活过重写`（`XADD s_wide 9223372036854775808-8` 竟然**写进去了**）、
  `XRANGE s_wide（上面那一问的阳性对照）`（那条 -8 真的留在流里）。
  链路上每一环都成立：那种写法过不了 ID 文法 ⇒ 重放整条拒掉 ⇒ 表顶退回到还活着的 `…-7`
  ⇒ 单调性闸失去参照。**这一支不会被"少一条记录"型的变异发现，只能被"写法"发现。**
- **上游权威**：`rioWriteBulkStreamID`（`aof.c:1136-1140`）里那一行
  `sdscatfmt(sdsempty(),"%U-%U",id->ms,id->seq)`（`:1139`），`%U` 就是无符号那一款；
  `XADD` 的条目 ID、`XSETID` 的表顶、`XGROUP CREATE` 的位置三只都走这一支函数。
- **改动**：`streamId(long, long)` 改为委托仓库里已有的唯一文法出口
  `StreamIdFormat.format`（`z-cache-common`，`:75-77` 用的正是 `Long.toUnsignedString`；
  命令层 6 处早就走它：`CommandHandler:2499 / :2583 / :2584 / :2818 / :2895 / :3241`）。**修的是写法，没有新增格式**：
  日志的形状、命令的条数与顺序都不变，所以线格式与 13g 那份一致。
- **判据**：上一格那条 `aofRewriteCarriesStreamKeysAcrossTheSwap` 加一种形状
  （`s_wide`：`XADD …-7` → `XADD …-9` → `XDEL …-9`，于是表顶只能由 `XSETID` 带过去，
  与 `s_orders` 同形但整段跨过 2^63），另加 1 格结构层 + 1 格行为层 + 1 格阳性对照
  （被拒的那一问不许顺手改掉内容 —— 它同时是"s_wide 真的活过了重写"的证据）。
- **牙**（同一份量具，`logs/teeth_run3.txt` 是加格子之后的第一跑，`logs/teeth_run4.txt` 是收口跑）：
  新增 M8（把 `streamId` 换回有符号拼接）→ 恰好红上面那 3 格。三格的预期集是**跑出来的**：
  run3 报出 M1/M2/M5 各"多"了新的格子，逐个想过因果之后按实测放宽 ——
  M1（整族消失）16 → 19 格、M2（摘掉 XSETID）2 → 5 格（摘的正是 `s_wide` 也要的那一条）、
  M5（改用自动 ID）6 → 7 格（只漂"内容逐字对得上"那一格，`s_wide` 的 XSETID 写法没动，
  所以表顶那两格**保持绿**，这是三格里唯一一支不能顺带放宽预期集的）。
  `CONTROL → OK`，`SUMMARY mutants=9 bad=0`，八支全部「漏 无；多 无」。
  **上一格正文里"改前 16 格"与"mutants=8"两句按本段作废**，那是加形状之前的读数。
- **基线不变 `906`**（`358 + 411 + 135 + 2`，`mvn -o -B clean test` rc=0 / BUILD SUCCESS，日志
  `~/.cache/zcache_gauges/logs/full_wide_124645.log`）：本格加的是**格子不是 `@Test`**，
  测试条数不动是分母正常的表现，不是没测。
- 250 那一侧仍然没有实测：本轮全部读数单机。

#### 只留快照重启，Stream 一族又是第一个没的：六型里最后一族进不了 RDB（13h）

- **来账**：13g 让流键活过了 AOF 重写，而 README 与 `_doc/001_arch/01-module-structure.md` 里那句
  "Stream 也仍然进不了 RDB 快照（见 CHANGELOG 13e ③、13g）"是**当时的事实**：`RdbPersistence`
  从来没问过 `getAllStreamEntries`，SAVE 之前 `DEL` 掉 AOF 之后，六种类型里只有五种回得来。
  配了 `save` 策略的那一侧只写得下五型，等于"持久化"这项宣传对一整族键是空的。
- **改动前的形状**（生产侧就是 `a215561` 那份字节，只往测试里加判据；日志
  `~/.cache/zcache_gauges/logs/rdb_prefix_125207.log`，`Tests run: 1, Failures: 1`）：红 **13 格** ——
  `DBSIZE`、`XRANGE s_live`、`TYPE s_live`、`XLEN s_live`、`TTL s_live`、`表顶活过快照`、
  `组重建之后还喂得动`、`EXISTS s_empty`、`TYPE s_empty`、`空流的表顶`、`高位的表顶活过快照`、
  `XRANGE s_wide（上面那一问的阳性对照）`、`XRANGE s_in3 在 3 库`。
  那一跑的测试字节还没有 `s_dead` 与那 2.1 秒的显式空档（下一节"牙"之前才补的），所以是 13 格；
  收口那一跑里同形的那一支（R1）是 **14 格**，多出来的正是加固后的 `组的读数位置`。
- **上游权威**：`RDB_TYPE_STREAM_LISTPACKS = 15`（`rdb.h:93`），写点 `rdbSaveObjectType`
  （`rdb.c:625`）里的 `:656`，读点 `rdbLoadObject` 的 `:1698`。顺序照 `rewriteStreamObject`
  那一族在 AOF 侧的形状（条目 → 表顶 → 组，`aof.c:1172-1266`），13g 已经按这个顺序导出过一次。
- **改动**：
    - 类型字节是 `5` 而不是上游的 `15`：我们的魔数是 `ZCHRDB`、`RDB_VERSION = 1`、逐库段头、
      尾部 XOR 校验，**格式本来就不是 Redis 的**，往自己的序数里连续排（`0..4` 五型之后）比
      引进一个"看着像上游但其实谁都读不了"的 15 更诚实。这一点写在 `TYPE_STREAM` 的注释里。
    - 负载三段：表顶两段 `long` + 逐条（ID 文本 + 字段名值）+ 逐组（组名 + 读数位置两段）。
      表顶那两段存的是**位模式**而不是十进制文本 —— 正是 13g-2 那一支（`>=2^63` 写成负号）
      教的那一课，二进制格式天然免疫，`s_wide` 那一格就是为这一维留的。
    - `readStream` 装配完条目之后**再压一次表顶**（`stream.setLastId(topMs, topSeq)`）：
      `addEntry` 里那道只向前走的 `updateCounters` 会把表顶顶到最大那条条目上，而
      "表顶停在被 XDEL 掉的那一条"正是这一族唯一不能从条目表推演出的信息。
    - 组数取自先落一摊非空的 `LinkedHashMap`，不是先写 `groupNames().size()` 再跳空迭代 ——
      `ConsumerGroup` 那张表是 `ConcurrentHashMap`，`XGROUP DESTROY` 可以在计数与写出的中间
      抽走一只；那种"计数 3、实际写 2"会让读侧整个错位，而校验和看着还是对的。
    - `StoreAccessor.restoreStream(db, key, stream, expireAt)` 与另外五家的 `restoreX` 对称；
      那一腿整只 `put`（`StreamStore.put`，新增），不逐条 `addEntry`，并共用同两道闸
      （`diedWhileOffline` 判死、`armAfterRestore` 挂时刻）。
- **判据**：新增 `RedisServerLifecycleTest#saveSnapshotsStreamKeys` —— 一张表 **22 格**、
  一次合并断言（JUnit 见第一条红就抛，分三批断言会把"三样一起坏"归给先红的那一批，13g 那条
  踩过一次）。三种形状各问一遍表顶：`s_live`（有组、`XACK` 过、4-1 已 `XDEL` ⇒ 表顶与
  "活着的最小 ID"是两件事）、`s_empty`（空流也要把表顶留在 5-1）、`s_wide`（整段跨过 2^63）；
  两枚根本不该回来的（`s_gone` 快照前就 `DEL`、`s_dead` 在**显式睡掉的 2.1 秒空档**里到点，
  且 SAVE 之前它必须还活着 —— 那才是"进了文件、加载那一判再抹掉"）；分库一枚 `s_in3` 加一格
  回到 0 库的反证。每问"该被拒"的都压一格猎物（4-2 / 5-2 / `-8`），否则"拒掉了"和"根本没有"
  分不开。
- **牙**（新量具 `~/.cache/zcache_gauges/rdb_stream_mut/teeth.py`，七支 + 控制组）：
  run1（`logs/teeth_run1.txt`）先量出两处，run2（`logs/teeth_run2.txt`，13:09:11—13:10:00）是收口跑：
  `CONTROL → OK`，`SUMMARY mutants=8 bad=0`，七支全部「漏 无；多 无」，红集大小
  **R1 14 / R2 4 / R3 2 / R4 1 / R6 14 / R7 4 / R8 3**。
    - R1 枚举口交回空表、R2 读侧不回写表顶、R3 读侧不重建组、R4 加载腿丢掉 `armAfterRestore`、
      R6 `DbSection.totalEntries()` 不算流（于是只含流键的库整段不写）、
      R7 写侧表顶按最后一条条目渲染、R8 写侧不给流键写时刻。
    - **R5 这一支没有**：摘掉加载腿那道 `diedWhileOffline` 闸不构成一次成功的判红，读码即可断定，
      不必注入 —— 时刻走 `armAfterRestore` → `MemoryStore.armExpiry`（`MemoryStore.java:692-702`）
      在 `relativeMillis <= 0` 那一支直接 `removeAnyType`（`:826-836` 六型通删，`:833` 含流表），
      键当场就没了。两道闸在流这一族上是**冗余**的，因此 `MemoryStoreAccessor:222-225` 那段
      "没有这道闸会被当成永久键写回去"的说法对**这一族不成立**（它成立的条件是加载后没人再问时刻）；
      真正有牙的是 R8（文件里根本没有那一行）。
- **三处覆盖面缺口，照实记不记分**：
    - R1 与 R6 的红集**逐字相同**（14 格）：枚举口交回空表时段头计数跟着是 0、整段照样不写，
      落盘形状完全一样。
    - R2 与 R7 的红集同样**逐字相同**（4 格，`diff` 过）：坏在写侧的表顶还是坏在读侧的表顶，
      从"重启后 4-0 塞得进"这一问看不出差别。
    - 把这两对分开要的是同一层东西 —— 直接读 `dump.rdb` 字节的**结构守卫**（像 13g 那一族的
      `结构层 …` 格子）。本格没有这一层。
    - 22 格里有 **7 格没有任何一支变异点得红**：`XLEN s_empty`（键根本不在时 XLEN 也回 `:0`，
      这一格结构上分不开"空流"与"没有流"，那层由 `EXISTS s_empty`/`TYPE s_empty` 扛）、
      `XADD s_live 4-2（阳性对照）` 与 `XADD s_empty 5-2（阳性对照）`（猎物格，职责是让上面
      那一问不是空话，一族消失时它们照样"成功"）、`EXISTS s_gone`（要点红它得凭空多一枚键）、
      `SELECT 3`/`SELECT 0`/`EXISTS s_in3 回到 0 库`（这三格只在负载写歪、字节错位时才红，
      而七支变异交回的都是形状完好的文件 —— 半损坏文件那一注入得连校验和一起说，未做）。
- **顺带（同一格在 AOF 那一族里也是空过的）**：`组的读数位置` 原先只问"领没领到条目"，
  组压根不存在时报的是 `-NOGROUP`，里面没有 `\d+-\d+` ⇒ **那一问把"没有组"读成"位置卡住了"**。
  两处（AOF 与 RDB 两支测试同形）一起改成"报 `-ERR` 也算红"。13g 那份量具据此重跑
  （`stream_rw_mut/logs/teeth_run6.txt`，13:11:42）：`M1` 19 → **20 格**、`M3` 2 → **3 格**，
  八支仍全部「漏 无；多 无」，`SUMMARY mutants=9 bad=0`。**上一格正文里 M1/M3 的格数按本段作废**，
  那是加固之前的读数。
- **基线 906 → `907`**（`358 + 412 + 135 + 2`，core 411 → 412 是本格新增的那 1 个 `@Test`；
  `mvn -o -B clean test` rc=0 / BUILD SUCCESS，日志
  `~/.cache/zcache_gauges/logs/full_13h_131221.log`）。
- 250 那一侧仍然 ssh 不通（sshd banner 不发）：本轮全部读数**只有单机**。

#### 机器修好了两台，踩下它的那一脚一直没有：`BGREWRITEAOF` 接上命令层（13i）

- **来账**：13g 把"导出最小命令集"修好、13h 把同一族送进快照，而那一整段在命令层**没有入口** ——
  `z-cache-core/src/main` 里 `BGREWRITEAOF` 0 命中（阳性对照同尺：`BGSAVE` 1 命中），
  `AofPersistence` 那个专门为重写开的 `rewriteExecutor` 线程池从头到尾没被提交过任务。
  README 与 `_doc/001_arch/01-module-structure.md` 当时写的"不要指望 BGREWRITEAOF"是真话 ——
  本节把那句话改口，两句旧话已按实测同步改掉。
- **改动前的形状**（生产侧就是 `5d053ef` 那份字节，工作区只加判据；日志
  `~/.cache/zcache_gauges/logs/prefix_bgrewriteaof.txt`）：`Tests run: 1, Failures: 1`，红 **3 格** ——
  `BGREWRITEAOF 的答复` 与 `没配 dataDir 时如实拒绝` 两句都是 `-ERR unknown command 'BGREWRITEAOF'`，
  `结构层 k 只剩一笔` 读回的是 `3`（三笔流水原样留在日志里）。改前那一跑共 13 格、红 3 绿 10
  （"正在重写"那时还是一格，收口时拆成两格 ⇒ 本表格子数按 `13 → 15` 走）：**其余十格改前就是绿的**：
  表顶、组、时刻、分库、重启读回那些格子量的是"换过一份日志之后别弄坏东西"，机器好着而没人踩
  油门，它们当然全绿 —— 这一族的缺口结构上就只能由"答复的原文"与"日志有没有真的换过"两格看见。
- **上游权威**（本轮现读的行号）：`bgrewriteaofCommand`（`aof.c:1629-1640`）三支 ——
  已经有人在重写 → `:1631` 的 `-ERR Background append only file rewriting already in progress`；
  有 `BGSAVE` 在跑 → `:1633-1634` 挂一个 `aof_rewrite_scheduled` 并回 `+...scheduled`；
  否则 `:1635` 起子进程、`:1636` 回 `+Background append only file rewriting started`
  （打的是 `addReplyStatus`，也就是**简单字符串**而不是错误）。
  `rewriteAppendOnlyFileBackground`（`:1569`）在 `:1573` 一并挡住"两种子进程任一在跑"。
  命令表 `server.c:248` 那一行是 `{"bgrewriteaof",bgrewriteaofCommand,1,"as",…}`。
  全树搜 `append only mode is disabled` / `Cannot BGREWRITEAOF` **0 命中** ⇒ 上游这一支不查
  `appendonly` 档位，它只要一个写得出去的路径。
- **改动**：
    - `rewriteAof` 拆成"闸 + `rewriteInternal`"：闸那半边还是原来那三道（路径非空、必须 started、
      抢 `rewriting`），业务那半边一字未动 —— 线格式与 13g/13h 两份判据读的是同一份字节。
    - 新增 `isRewriteSupported()`（只读、不抛，命令层要的就是"能不能"而不是异常）与
      `rewriteAsync()`。**标志必须在<em>入队之前</em>抢**：线程池什么时候排到不可知，抢晚半步，
      期间进来的第二问就会看到"没人重写"而把同一份日志再排一遍。`RejectedExecutionException`
      那一支把标志还回去并回 `false` —— 排不上而不还，这份日志从此"永远有人在重写"。
    - **两处归还收成一处**（这是量具替我们找出来的缺陷，不是顺手重构）：`rewriteInternal` 的
      `finally` 与异步那个 lambda 的 `finally` 原本各还一次，摘掉任一处都不红 —— 也就是
      "忘了还标志"这一种坏法在两条路上各只测得到一半。归还现在只有 `rewriteInternal` 一处，
      两条路共用同一个出口。
    - 命令层 `case "BGREWRITEAOF"` → `handleBgrewriteaof()`。与上游的差别两处，都是方向性的：
      不 fork ⇒ 没有"排在 `BGSAVE` 之后"那一支（`+...scheduled` 这一版不存在，B4 就是钉这一句）；
      `aofFilePath` 只在带 dataDir 启动时才成立 ⇒ "没配 dataDir"是"没有一份日志可换"的对应物，
      那一支如实回 `-ERR BGREWRITEAOF is not supported: no data directory configured`，
      宁可回错也不许回一句 `started` 却什么都没干。
- **判据**：新增 `RedisServerLifecycleTest#bgrewriteaofStartsTheRewriteAndTheLogStaysAppendable`，
  一张表 **15 格**（数过：`seen` 的键 15 个，含 `expectTtlCell` 自命名的那一格）、一次合并断言。三格是这一族的新形状：`BGREWRITEAOF 的答复` 钉 `:1636` 原文；
  `结构层 k 只剩一笔` 钉"日志真的换过一份"（等的是**因**不是睡：5 秒有界空档内轮询到三笔收成一笔，
  换文件那一瞬间可能读到半截，`IOException` 只记账、下一轮再读）；`锁内第一问仍受理` 加
  `锁内第二问撞闸` 钉 `:1631` —— 这两格不许靠 sleep：重写排在 `appendCommand` 那把锁上，判据把
  锁攥在手里，排队那一份就走不完、标志就一直是 `true`，锁内连问两遍。**锁内只发 `PING` 不发写
  命令**（写命令的线程会来抢我们手里这把锁，我们又在等它的答复 ⇒ 两边互等，这一格会挂住整条腿）。
  锁放开之后那份排队的重写会跑完，而重写按构造幂等（导的是当前状态），所以"只留这一份 AOF 重启"
  的格子读回的还是同一形状。
- **牙**（新量具 `~/.cache/zcache_gauges/bgrewriteaof_mut/teeth.py`，五支 + 控制组，收口跑
  `teeth_run4.txt`）：`CONTROL → OK`，`SUMMARY mutants=6 bad=0`，五支全部「漏 无；多 无」，
  红集 **B1 2 / B2 1 / B3 1 / B4 2 / B5 3** 格，且五张红集**两两不同名**（实测从
  `[RED_CELLS=…]` 抽出来比过）：
    - `B1` 命令层只回一句 started、一个任务都不提交（广告与兑现分家）→ `结构层 k 只剩一笔` +
      `锁内第二问撞闸`；`B2` 标志改到工作线程里抢 → 只红 `锁内第二问撞闸`；
      `B3` 重写跑完不还标志 → 只红 `锁内第一问仍受理`；`B4` 把 started 写成上游 `:1634` 那句
      scheduled → `BGREWRITEAOF 的答复` + 第一问；`B5` 受理与拒绝两支接反 → 三格同红。
    - `B2` 与 `B3` 落在同一个"正在重写"格上，是**改判据之前**的形状：那一格原先是一条
      `if / else if` 链，第一问一坏就不看第二问 ⇒ 两支变异报同一个格名，分不开"抢晚"与"不还"。
      拆成两格之后两支各自点名，**这里改的是判据，而 B2/B3 的行为缺陷（两处归还）是另修的**。
    - run1 里 `B3` 是 COMPILE-BROKEN（把 `finally` 整块摘掉，`try` 成了孤儿 —— 量具自己的错），
      run2 里 `B3` 合法之后 **SURVIVED**，那一次红不了的不是判据，是代码真有两处归还；
      run3（收成一处归还）之后 `B3` 才有牙。**记这一笔是不许把"改判据让变异变红"当成"修了缺陷"。**
    - 未覆盖面照实记：`锁内第二问撞闸` 钉的是"标志在不在"，钉不了"重写<em>成功</em>"——
      受理之后导出失败（没有 `StoreAccessor`、换文件失败）答复已经交出去了，只剩一行
      `LOGGER.log(Level.WARNING, …)`，与上游子进程失败同形，没有任何格子看得见；
      同步腿 `rewriteAof` 在这一跑的 14 格里没有专属格子（13g/13e 那几格量的是它的导出与拒绝）。
- **两份旧量具据本格的测试字节各重跑一遍**（测试文件被三份量具共同 TRACKED，改一寸就得三处复验）：
  `rdb_stream_mut/teeth_run4.txt` 与 `stream_rw_mut/teeth_run8.txt` 都是 `bad=0`。
  ⇒ 13j 又动了这张判据表与 `AofPersistence` / `CommandHandler`，按同一条规矩三族一起复验，
  三份新读数（字节均为判据表 `92e1b715…` / `AofPersistence` `42658e48…` / `CommandHandler` `98216322…`）：
  `bgrewriteaof_mut/teeth_run_13j.txt` = `CONTROL → OK` + `SUMMARY mutants=6 bad=0`（红集仍是
  B1 2 / B2 1 / B3 1 / B4 2 / B5 3），`rdb_stream_mut/teeth_run_13j.txt` = `mutants=8 bad=0`
  （R1 14 / R2 4 / R3 2 / R4 1 / R6 14 / R7 4 / R8 3），`stream_rw_mut/teeth_run_13j.txt`
  = `mutants=9 bad=0`（M1 20 / M2 5 / M3 3 / M4 2 / M5 7 / M6 2 / M7 1 / M8 3）—— 三族与 13j 新装的那族
  在这一版字节上全绿，没有互相踩坏。
- **仍然没接上的（下一格）**：按体积触发的那一台 —— `auto-aof-rewrite-percentage` /
  `auto-aof-rewrite-min-size`（上游默认 100 与 64mb，`server.h:98-99`，`CONFIG GET` 里
  `config.c:1361 / :1363`）两项配置在 `CONFIG` 里查不到，也没有后台调度方在量日志体积。
  也就是说 13i 之后重写<em>有人能踩油门</em>，但<em>自动挡</em>还是没有。
- **基线 907 → `908`**（`358 + 413 + 135 + 2`，core 412 → 413 是本格新增的那 1 个 `@Test`；
  `mvn -o -B clean test` rc=0 / BUILD SUCCESS，日志
  `~/.cache/zcache_gauges/logs/full_13i_run2.txt`）。
- 250 那一侧仍然 ssh 不通：本轮全部读数**只有单机**。

#### 自动重写要先有分子与分母：`INFO` 里那两个 AOF 大小此前一个字都没有（13j）

- **来账**：13i 把"下一格"记成自动挡（`auto-aof-rewrite-percentage` / `auto-aof-rewrite-min-size`）。
  动手前先读上游那一台机器：`server.c:1301-1315` 那块算的是
  `base = aof_rewrite_base_size ? : 1`（`:1308-1309`）、`growth = aof_current_size*100/base - 100`（`:1310`）—— 分子分母两个数
  我们只有分子的一半（`appendedBytes` 有记账，但没有任何出口读得到它），分母**整个概念都不存在**；
  而上游把这两个数交出去看的那张嘴（`INFO` 的 `# Persistence` 段）在本仓**一个字段都没有**：
  `handleInfo` 只有 Server / Clients / Memory / Stats / Keyspace / Replication 六段。
  所以本格先装"看得见的大小"，自动挡留到下一格。
- **上游权威**（本轮现 grep `~/.cache/zcache_gauges/full5x/redis-5.0.14/src`，行号为那一跑所读）：
  段头 `"# Persistence\r\n"` `server.c:3358`；`aof_enabled:%d` 声明 `:3367`、取值
  `server.aof_state != AOF_OFF` `:3383`；`aof_rewrite_in_progress:%d` 声明 `:3368`、取值
  `server.aof_child_pid != -1` `:3384`；`aof_current_size` / `aof_base_size` 声明 `:3396-3397`、
  取值 `:3403-3404`，而这两行整个在 `if (server.aof_state != AOF_OFF)`（`:3394`）里面。
  两个字段的写入点更要紧：`aof_current_size`（`server.h:1080`）随每次写完自增（`aof.c:466`、`:480`），
  **换过一份之后是按 stat 重取而不是接着加** —— `aofUpdateCurrentSize`（`aof.c:1653-1665`，注释原文
  "normally the size is updated just adding the write length"）的调用点在载入收尾 `:865` 与重写收尾
  `:1772`；`aof_rewrite_base_size`（`server.h:1079`，"AOF size on latest startup or rewrite"）全上游
  只有三处赋值：初值 0（`server.c:1594`）、载入收尾 `aof.c:866`、重写收尾 `aof.c:1773`，后两处都紧跟
  在 `aofUpdateCurrentSize()` 的下一行。写命令不算它 —— 它是上面那个增幅的**分母**。
- **改动前的形状**（生产侧就是 `30ac37a` 那份字节 `697c5eca…` / `769b9bfa…`，工作区只加判据；日志
  `~/.cache/zcache_gauges/info_aof_sizes_mut/logs/prefix_head_bytes.txt`）：`Tests run: 1, Failures: 1`，
  全表 17 格里**红 16 格**，每一格的读数都是 `(这一行没有)`。唯一改前就绿的那格是否定式的
  "没起 AOF 时不报 current 长度" —— 整段不存在时它必然成立，所以它的牙不在这里，在 S5 那一支。
  同一跑里 `Files.size` 量到的盘上真实长度是 `0 → 179 → 151 → 203 → 180`（接手 → 写四笔 →
  重写之后 → 再写一笔 → 重开一代），也就是**被量的那台机器早就在正确地换文件**，缺的只是把它报出去。
- **改了什么**：`AofPersistence` 新增 `rewriteBaseBytes` 字段与 `getAofCurrentSize()` /
  `getAofBaseSize()` 两个出口；写入点跟上游一一对应 —— `start()` 接手一份已有日志时把底座对齐到
  `file.length()`（上游 `aof.c:865` + `:866`），`rewriteInternal()` 换完文件后
  `appendedBytes = new File(path).length()` 之后紧跟 `rewriteBaseBytes = appendedBytes`
  （上游 `:1772` + `:1773`）。`CommandHandler.handleInfo` 新增 `# Persistence` 段：`aof_enabled` 由
  `aof() != null && isStarted()` 喂，`aof_rewrite_in_progress` 由 `isRewriting()` 喂，两个大小放在
  `if (aofOn)` 里面（对应上游 `:3394` 那道闸）。
- **判据**：`RedisServerLifecycleTest#infoReportsTheRealAofSizes`，一张表 **17 格**跑一次断言一次，
  五个站点：接手那一刻 → 写四笔之后 → `BGREWRITEAOF` 之后 → 再写一笔 → 重开一代，末尾再来一台
  没配 `dataDir` 的。每个数都跟**同一次量具运行里** `Files.size` 量到的盘上长度对，不是跟另一个
  Java 字段对（`getAofCurrentSize()` 交回的是记账值 —— 上游也是这么办的，判据要独立才量得出记账错）。
- **量具抓出了判据自己的一个缺陷**（这条最值得记）：重写那一站的"跑完了"原先是等
  `aof_rewrite_in_progress` 归零 —— 也就是**等正在被审的那个字段**。S6（把该字段硬编码成 0）在
  run1 里因此红了 4 格：一等即中，接下来读的是还没换完的日志，base/current 全对不上，红的是时序
  而不是渲染。改成等 `rewriting` 标志归还（13i 钉过它只有一个归还点，还的时候文件已经换完）之后，
  S6 只剩"重写进行中 in_progress 为 1"这一格 —— 那才是"渲染没跟着状态走"这一种坏法。
- **有牙量具**：`~/.cache/zcache_gauges/info_aof_sizes_mut/teeth.py`（TRACKED = 这张判据表 +
  `AofPersistence` + `CommandHandler`），`teeth_run3.txt` = `CONTROL 未变异 → OK` /
  `SUMMARY mutants=8 bad=0`，七张红集两两不同：S1 追加不记账 `{写了几笔之后 current, 再写一笔 current}`、
  S2 重写收尾接着加 `{重写之后 current, 重写之后 base, 再写一笔 current, 再写一笔 base}`、
  S3 底座跟着每笔写涨 `{写了几笔之后 base, 再写一笔 base}`、S4 载入不摆底座 `{重开一代 base}`、
  S5 丢掉那道闸 `{没起 AOF 时不报 current 长度}`、S6 in_progress 硬编码 `{重写进行中 in_progress 为 1}`、
  S7 重写收尾不挪底座 `{重写之后 base, 再写一笔 base}`。两支不注入、只记码：
  "把 `aof_enabled` 写成只看对象在不在"在这一族上量不出来（没配 `dataDir` 时 `aof()` 就是 `null`，
  两种写法都回 0）；"`aof_base_size` 报成 current 的值"与 S3 落在同一张红集上 —— 重写收尾那一刻
  base 与 current 本来就相等，判据结构上分不开"字段串位"与"写侧多写一次"。
- **这一段还不带的上游字段**（各有各的欠账，不硬凑）：`loading` 与 `rdb_*` 一族（RDB 侧的
  `lastsave` / bgsave 状态位我们没有）、`aof_rewrite_scheduled`（不 fork 就没有"排到下一轮"这一支，
  13i 答的是 `-ERR already in progress`）、`aof_last_bgrewrite_status` / `aof_last_rewrite_time_sec` /
  `*_cow_size`（重写失败目前只进 `LOGGER`，没有状态位可报）。另外 `INFO <未知段名>` 本仓回空串而
  上游回错误，那是另一格。顺带量到 README「监控集成」那一节（约 299-305 行）广告出去的
  `instantaneous_ops_per_sec` / `maxmemory` / `cluster_state` 三个名字在 `handleInfo` 里 0 命中
  （同尺阳性对照 `blocked_clients` 1 命中；`CLUSTER` 也 0 命中），而 Memory 段报的是 `max_memory`
  —— 那是另一格，已单独登记，不混进本节。
- **仍然没接上的（下一格）**：自动挡本身 —— `auto-aof-rewrite-percentage` / `auto-aof-rewrite-min-size`
  （默认 100 与 64mb，`server.h:98-99`；配置文件解析后落到 `config.c:501` / `:509`；`CONFIG SET`
  运行时口 `config.c:1160-1161`（数值，`0..INT_MAX`）与 `:1262-1263`（内存量，`0..LONG_MAX`）；
  `CONFIG GET` `config.c:1361-1362` / `:1363-1364`）—— 本仓根本没有 `CONFIG` 命令
  （现读 `CommandHandler` 的 case 表：`CONFIG` / `COMMAND` / `ACL` / `TIME` / `SWAPDB` 各 0 命中，
  阳性对照同一次量具 `INFO` / `SLOWLOG` / `BGREWRITEAOF` 5 命中），也没有后台调度方去算
  `server.c:1302-1313` 那个增幅。13j 供上的是它的**分子与分母**，也就是那一格的两条腿。
- **基线 908 → `909`**（`358 + 414 + 135 + 2`，core 413 → 414 就是本格新增的那 1 个 `@Test`；
  `mvn -o -B clean test` BUILD SUCCESS，日志 `~/.cache/zcache_gauges/logs/full_13j_run1.txt`，
  分模块读数由 `tally_log.py` 现算：`MODULES=4 run=909 failures=0 errors=0 skipped=0`）。
- **第二台机器补跑（09-27 14:2x）—— 本 entry 上一版写的"本轮全部读数只有单机"到此作废**：
  250 已恢复 ssh，在 `13b8c6b` 的**干净 clone**（`~/zcache-250t/z-cache-13j`，
  `git status --porcelain | wc -l` = 0）上跑完整套件（日志 `~/zcache-250t/logs/full_13j_250.txt`，
  它自己写的 `Finished at: 2026-09-27T14:28:22+08:00`）：`BUILD SUCCESS`，`tally_log.py` 报
  `MODULES=4 run=909 failures=0 errors=0 skipped=0`（`358 + 414 + 135 + 2`），与本机的
  `logs/full_13j_run1.txt` **逐字节相同** —— 两份 tally 文本 `diff` 无差异、`md5` 同为
  `dbc023d1602e9b6e9f5fc49158ddb1ac`（原始日志分别是 `8538bdeb…` 本机 / `720b5877…` 250，
  差异只有时刻）。两边用的是**同一把尺**：250 上那份 `tally_log.py` 是从本机 `scp` 过去的，
  md5 同为 `3eabf408ad99f3edf2dcba1a635908fa`。
  **在 250 上跑的只有全量套件**（本格那 17 格判据跟着 909 条一起过）；四族注入牙仍然
  **只在本机量过**，250 那边一条都没复算 —— 别把这段读成"两台机器都有牙"。
- **250 上"照着 PATH 跑"是跑不起来的，原因不在代码**：那台机器的默认 `mvn` 是 **3.6.0**
  （`/usr/bin/mvn`）。当场证据（14:34 重跑并存盘，`~/zcache-250t/logs/full_13j_250_mvn360.txt`
  64 行、md5 `f99da8c254c8f4f709c3211d69f240e6`、rc=1）：
  `The plugin org.apache.maven.plugins:maven-compiler-plugin:3.13.0 requires Maven version 3.6.3`，
  死在 `z-cache-common` 的 `default-compile`，收尾就是那句 `mvn <goals> -rf :z-cache-common`。
  换 `~/maven3914/bin/mvn`（3.9.14；同机另有 3.9.6 也满足）后一次跑通。
  **往后在 250 一律显式写 `~/maven3914/bin/mvn`**，别再拿 `PATH` 上那个。
  （14:23 的第一次失败日志被第二次运行按同一路径覆盖了，当时看到的是 61 行 —— 本条引的是
  重跑那份存下来的，不是记忆里的那份。）
- **行号校正（三处，都在本 entry 里，因为两行折行的宏只点了第二行）**：`CONFIG SET` 的
  `auto-aof-rewrite-percentage` 是 `config.c:1160-1161`（宏名在 1160，字段与区间 `0,INT_MAX` 在 1161），
  `auto-aof-rewrite-min-size` 是 `config.c:1262-1263`（赋值在 1263）；`CONFIG GET` 两项分别是
  `config.c:1361-1362` / `:1363-1364`。上一版各写一个行号，读起来像"两个判据各占一行"。
  同一次现读还纠正了增幅那一段的指代：整块是 `server.c:1301-1315`（五个条件 `:1302-1306`、
  `base = … ? : 1` 在 `:1308-1309`、`growth` 在 `:1310`、比较在 `:1311`、真的去重写是 `:1313`），
  上一版写的 `server.c:1305-1312` 两头都切在了句子中间。

#### 那一拍终于挂上了：100ms 量一次增幅，两条旋钮各自有主（13k）

- **来账**：13j 供上的是自动挡的**分子与分母**（`aof_current_size` / `aof_base_size`），
  并在结尾明写"触发口只有 13i 那一脚人工油门，按体积自动重写仍然没有"。这一轮做那一格。
- **上游那一拍的完整形状**（本轮在 `~/.cache/zcache_gauges/full5x/redis-5.0.14/src` 现读）：
  整块是 `server.c:1301-1315`，注释 `/* Trigger an AOF rewrite if needed. */` 在 1301，
  五个条件 `:1302-1306`（`aof_state == AOF_ON`、`rdb_child_pid == -1`、`aof_child_pid == -1`、
  `aof_rewrite_perc` 非零、`aof_current_size > aof_rewrite_min_size` 严格大于），
  `base = aof_rewrite_base_size ? : 1` 在 `:1308-1309`，`growth = (aof_current_size*100/base) - 100`
  在 `:1310`，`if (growth >= server.aof_rewrite_perc)` 在 `:1311`，真的去
  `rewriteAppendOnlyFileBackground()` 在 `:1313`。它待在 serverCron 的 `else` 分支里，
  也就是"这一台当前没有后台保存 / 后台重写"那一支；节奏 = `return 1000/server.hz`（`server.c:1374`），
  而 `CONFIG_DEFAULT_HZ 10` 在 `server.h:83` ⇒ **100ms 一拍**。默认值 `AOF_REWRITE_PERC 100`
  与 `AOF_REWRITE_MIN_SIZE (64*1024*1024)` 是 `server.h:98` / `:99` 两行宏。
- **做了什么**（全在 `AofPersistence`，命令层一个字没动）：
  ① 两条常量 `AUTO_AOF_REWRITE_PERCENTAGE = 100`、`AUTO_AOF_REWRITE_MIN_SIZE = 64L*1024*1024`；
  ② 两条 `volatile` 旋钮配 getter/setter，负数按上游的下界拒掉（`config.c:1160-1161` 那一项是
  `0,INT_MAX`，`:1262-1263` 是 `ll,0,LONG_MAX`），消息用上游原文
  `Invalid negative percentage for AOF auto rewrite`；
  ③ `shouldAutoRewrite(aofOn, rewriteInProgress, current, base, percentage, minSize)` ——
  把那五个条件加增幅原样写成一个纯函数，表判据直接打它，不需要真起服务器；
  ④ `checkAutoRewrite()` 是走真实实例的那一份，够条件就交给 `rewriteAsync()`；
  ⑤ `start()` 末尾挂 `AUTO_REWRITE_TICK_MS = 1000L / 10` 的 `scheduleAtFixedRate`，`stop()` 里撤。
  这一拍**与 `fsyncFuture` 是两支**，不是把检查塞进每秒刷盘那一拍里：上游本来就是两件事 ——
  写侧刷盘是 `aof.c:337 flushAppendOnlyFile()`（连缓冲为空都要问一次"是不是还欠着 fsync"，
  `:342-352`），而体积增幅这一拍在 serverCron。塞成一支的坏法是 `appendfsync no` 会顺手把自动挡关掉。
- **三条与上游的差别，说清楚不藏着**：
  ① 节奏写死 100ms。上游随 `server.hz` 变（`server.c:1374`），而改 `hz` 要走 `CONFIG SET`，
  这一版还没有 `CONFIG` 命令族 ⇒ 旋钮只有 Java 侧的 setter，运维口上够不着；
  ② 上游这一拍读的是 `server.aof_current_size`，那个数由 `aofUpdateCurrentSize()` 按 `fstat` 重取
  （调用点 `aof.c:1772`，紧挨着 `:1773` 把 `aof_rewrite_base_size` 一并挪过去）；这里读的是 13j
  那两本账（写入侧累加 + 重写末尾按 `java.nio` 真实长度对账），**同样是盘上字节，取的路径不同**；
  ③ 五个条件里只落了四个 —— 上游还要求 `rdb_child_pid == -1`（没有后台保存在跑，`server.c:1303`），
  而本版 `shouldAutoRewrite` **没有这一项**。这不是"结构上不可能重叠"：`RdbPersistence` 有自己的
  `bgSaving` 计数（`:171`，BGSAVE 据此拒绝并发快照）与 `saveAsync()`（`:351`），
  后台保存是真的在另一条线程上跑，所以**自动挡可以在一次 BGSAVE 进行中去换日志**。
  本轮把它记成缺陷而不是补进代码，因为补它要先把 `RdbPersistence` 那个计数露出一个读口
  （现在类外无人读它），耦合方向得先定：记作下一格，见本节末"下一格"。
- **判据：38 格，三张表，每格带反方向的邻居**：
  `AofAutoRewriteTest#growthRuleMatchesTheUpstreamCronCondition` 17 格 —— 上游那五个条件里**落地的
  四个**各有一格"关掉它"与一格"开着它才走"（第五个 `rdb_child_pid` 没有格子，因为它根本没进代码，
  见上面差别 ③），边界各钉两侧（`current == minSize` 不重写 / 超过一寸才重写；
  `growth == perc` 重写 / 差一个百分点不重写），`base = 0` 那一格钉的是"按 1 算而不是除零"，
  两条默认常量各一格，两个负数旋钮各一格且钉的是"拒了而且原值没动"；
  `theInstancePathUsesItsOwnTwoSizes` 10 格 —— 走真实实例与真实日志，其中
  **"盘上那份真的换小了（同一把键四笔只留一笔）"这一格只问 `java.nio` 眼里的文件长度，
  不读我们自己的任何记账**，另有一格反证"perc=0 那一臂不是因为没数据才不重写"（那八笔确实落到了盘上）；
  `RedisServerLifecycleTest#theScheduledTickStartsTheRewriteByItself` 11 格 —— 真起一台服务器，
  **等的是"日志里 `SET` 记录数从 5 塌成 1"这个盘上读数**（13j 立的那条：等待信号不许是被审的字段），
  四个臂各自配阳性对照：`perc=0` 之后"把地板放回 0 ⇒ 同一台马上又换得动"（上一臂不是量具瞎）、
  `appendfsync no` 那一臂证明这一拍不绑在刷盘档位上、没到地板那一臂证明"增幅再大也不谈"。
- **有牙量具**：`~/.cache/zcache_gauges/auto_rewrite_mut/teeth.py`，11 支变异，
  `logs/teeth_run2.txt` = `CONTROL 未变异: Tests run 3 Failures 0 Errors 0 -> OK` +
  `SUMMARY mutants=11 bad=0 control=OK`，逐支判红 T1 4 / T2 1 / T3 3 / T4 10 / T5 1 / T6 1 /
  T7 4 / T8 1 / T9 2 / T10 4 / T11 2。预期红集是从第一遍（`logs/teeth_run1.txt`，11 支全 RED-WRONG
  因为预期刻意留空）的实测红行**由脚本回填**的，一格都不是我手敲；回填后又机械比对过
  "脚本里的 11 组 == run1 里的 11 组"。这一遍顺手改掉量具自己两处坏：分母把控制组也算成一个变异
  （实际产出 `mutants=12`，而本文件顶部写的验收行是 `mutants=11` ⇒ **那条绿线在这把尺下永远打不出来**），
  改成"分母只数变异、控制组单列一列"；T9 第二枚锚点按多行写而那句 `throw` 实际在一行内，命中 0 处，
  12 枚锚点逐枚 dry-check 到"恰好 1 处命中"才开跑。
- **T7 这一支单独记，它量到的是判据的覆盖面而不是对错**：把 `start()` 里
  `applyAutoRewriteScheduler();` 那一行删掉，前两张表 27 格**一格都不红**，红的只有 E2E 那 4 格
  —— 也就是"到底有没有人挂这一拍"只有真起一台服务器才量得出来。反过来说：这 4 格的代价是秒级的
  等待，不能靠它们当快速回归，所以"该不该重写"这个纯函数那 17 格是必须独立存在的一层。
- **基线与复跑命令**：`mvn -o -B clean test` BUILD SUCCESS（46s），
  `python3 ~/.cache/zcache_gauges/tally_log.py ~/.cache/zcache_gauges/logs/full_13k_run1.txt` 报
  `MODULES=4 run=912 failures=0 errors=0 skipped=0`（`358 + 417 + 135 + 2`；core 414 → 417
  正是这三张表的三支 @Test）。**这一轮只有本机**，250 上一格都没复算（13j 那条双机逐字节对账
  是上一轮的事，别顺延到本轮）。本轮动过 `AofPersistence` 与 `RedisServerLifecycleTest`，
  而 `aof_rw_mut` / `bgrewriteaof_mut` / `fsync_mut` / `info_aof_sizes_mut` / `stream_rw_mut`
  五族的 TRACKED 集里都有这两份文件之一 ⇒ 它们 13j 时段的读数对这副字节**作废**，
  复跑结果紧跟着记在下一条（未复跑完之前，那一格按"待主编复跑"读）。
- **下一格（13l，两件事，先后由这一节的两条差别决定）**：
  ① 把 `rdb_child_pid == -1` 那一项补进那一拍（差别 ③ 记的就是它，先要给 `RdbPersistence.bgSaving`
  露一个读口并定耦合方向），补完在表里加一格"后台保存在跑 ⇒ 这一拍不许换文件"并给它一个反向邻居；
  ② `CONFIG GET` / `CONFIG SET` 把这两条旋钮接到命令层 ——
  本轮现读的负数下界（`config.c:1160-1161` / `:1262-1263`）现在只有 Java 侧的 setter 在守，
  仓库里 `case "CONFIG"` / `"COMMAND"` / `"ACL"` / `"TIME"` / `"SWAPDB"` 五个标签**一个都没有**
  （现读：`grep -rn 'case "CONFIG"' --include='*.java' z-cache-core/src/main` 零命中，
  同一条管道的阳性对照 `grep -rn 'case "BGREWRITEAOF"'` 命中一处
  `CommandHandler.java:432`）。

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
  `mvn -o -B clean test` 全量 **358 + 379 + 134 + 2 = 873 例全绿，0 failures / 0 errors / 0 skipped**
  （日志 `~/.cache/zcache_gauges/gate_postclean.log`，`tally_log.py` 聚合；错误码这一支加进
  1 条协议层用例，core 从 378 到 379；取键闸门那一支的记录是 358 + 378 + 134 + 2 = 872，
  再往前 358 + 377 + 134 + 2 = 871、357 + 374 + 134 + 2 = 867；更早那两节写的"832"与
  "96 + 340 + 133 + 2 = 571"都是旧刻度，一并留档）。这 873 跑在 `4177a37` + 本文件所提交的
  三个源码字节上，跑前跑后 `CommandHandler.java` 的 md5 都是 `8ecc6567…`。
- 这类跨实例作用域缺陷只在"整模块连跑"的形态下现形，所以按 `-Dsurefire.runOrder=random`
  把 common+core 连跑 9 次（3 + 6 两批）：**7 次 324 + 372 全绿，2 次不是**。执行顺序确实
  变了（三批的 md5 `355a02d4…` / `0b99054e…` / `911368ea…` 互不相同，不是只传了个开关）。
- 那 2 次红的归因只做到一半：6 连跑第 4 次红在
  `saveSnapshotsEveryDatabaseAndTheirTtls → startAndWait` 的"线程死了还没开始监听"，
  端口探测与 bind 之间被抢占是**嫌疑**而不是证据；3 连跑第 2 次当时脚本没留住失败名，
  只剩一个 `Errors: 1`，无法归因。为此三个服务器测试类的 `startAndWait` 现在把那条
  服务器线程带回来的异常一起报出来（旧版只留下一段没人在读的栈），下次红由异常自己说。
  **下一轮就用上了**：XGROUP 那支变异重跑时同一批的邻居格报出
  `server thread died before listening on 63670 —— 该线程带回来的异常: java.net.BindException`，
  紧接着 `lsof -nP -iTCP:63670` 抓到占用者是本机代理的一条 `FIN_WAIT_2` 出向连接
  （详见"已知边界"里那一格）—— 那句由异常自己说出来的话，正是这一轮从"嫌疑"变成"实证"的入口。
- `CommandHandler.streamTypeConflict(key)`：stream 一族"这枚键名被别的类型占着吗"的单一实现，
  按上游的 11 个位置各插一处（XADD / XLEN / XRANGE+XREVRANGE / XDEL / XTRIM / XREAD /
  XREADGROUP / XGROUP / XACK / XPENDING / XINFO，`CommandHandler.java:2491,2535,2567,2592,2617,2670,2734,2789,2838,2865,2903`）。
  位置本身是判据的一部分，所以没有做成一处统一的入口检查。
- `RedisServerProtocolSemanticsTest.streamFamilyHoldsTheSameOneTypeInvariant`：真流键上 18 处
  stream 调用的阳性对照 + 5 种占位类型 × 18 支命令（90 条）的 `assertEquals` 原文扫描
  + "闸门不改写字节" + 判序与整条作废面。配套 `expectWrongType` helper（不再用 `startsWith`）。
- 取键闸门的具名变异脚本 `~/.cache/zcache_gauges/gate_mut.py`（18 支，快照 `gate_snapshot/`，
  还原只从本次快照 `cp` + md5 对账，互斥锁 `mut.lock` 带 pid 校验）。
- 错误码这一支的具名变异脚本 `~/.cache/zcache_gauges/code_mut.py`（改前 12 支；本轮加到 **25 支**：
  P1-P12 错误码 + Q1-Q13 `COUNT`，`code_mut.py anchors` 报 `25 支探针 / 25 个锚点`，快照 `code_snapshot/`，
  同一套"只从本次副本还原 + md5 对账 + 带 pid 的锁"）。它的选取器按**谁读这行代码**派生，
  不按主题派生 —— 这一点是 P5 那一次 SURVIVED 换来的（见上面那一节）。
  **快照的保质期是"下一次改动"，不是"下一次运行"**：本轮开跑 Q 族之前，盘上的快照还是**上一支**
  （改前错误码那一版，md5 `8ecc6567…`），而工作树已经是 `COUNT` 那一版（`738e0850…`）——
  照它跑，每支探针收尾都会把未提交的 `COUNT` 修复按字节还原掉。所以先重打快照、
  再跑，且每支的还原行都得回读成 `738e0850…`（13 支全是）。
  两支 battery 留档：`battery55.txt` 46 行（改前 `battery55.prefix.tr` / 改后 `battery55.post`）、
  `battery56.txt` 15 行（改前 `battery56.pre` 由 HEAD 树建出的 jar `0f4e6474…` 量得 /
  改后 `battery56.post` 由 jar `eeb88c1b…` 量得）。
- `COUNT` 这一支的电池 `battery57.txt` 27 行（4 行建流 + 22 行判据 + `XLEN` 收尾）：改前
  `battery57.pre4` 由 jar `eeb88c1b…`（= HEAD 那棵树，上一轮留档）量得、改后 `battery57.post4`
  由 jar `4e447b61…`（工作树）量得，两侧各 `wrote=27 lost=none` 记在 `count_replay.log`，
  且与更早的 `pre3` / `post3` 逐字节相同（同一对 jar 重放两遍一致才算数）。
  两份 jar 都留了副本（`jar_postfix.whl`、`jar_countfix`），因为 `mvn clean` 会删掉 `target` 里那枚。
- `XADD / XTRIM` 这一支的电池 `battery58.txt` 46 行（`:1` PING、`:2-:4` 建流、`:5-:44` 判据、
  `:45 :46` 收尾核 `XLEN`/`GET` 的副作用）：
  改前 `battery58.pre`、改后 `battery58.post`，翻 16 行；本轮又把两侧各重放了一遍做可复现核对
  （`battery58.pre2` 与 `pre` 逐字节同、`battery58.post2` 与 `post` 逐字节同，两侧仍各
  `wrote=46 lost=none`，读数在 `b58_replay2.log`）。用的两枚 jar 都留了副本：
  `jar_countfix`（整包 `4e447b61…`、`CommandHandler.class` 的 md5 `b9a459b4…` = HEAD `c50b7a4` 那棵树，
  即本轮的改前面）与 `jar_xaddtrim`（整包 `7f579dc5…`、class `51b20f9a…` = 工作树
  `7f335e12…`）。**整包 md5 只作参照不作身份**：这枚 shaded jar 不是字节可复现的（zip 时间戳会漂），
  跨重建判同一律取 class 级 md5。
- XGROUP 这一支的电池 `battery59.txt` 39 行（`:1` PING、`:2` 留一枚 String 键给类型闸与收尾、
  `:3-:6` 建流与两个组、`:7-:31` 三道闸加分派的判据、`:32-:36` 为 DELCONSUMER 造一份 2 条的 PEL、
  `:37 :38` 首删回条数 / 再删回 `:0`、`:39` 收尾核 `GET` 的副作用没被波及）：改前 `battery59.pre` 由
  `jar_xaddtrim`（整包 `7f579dc5…`、class `51b20f9a…` = HEAD `7960050` 那棵树）量得，
  改后 `battery59.post` 由 `jar_xgroup`（整包 `9bf2cb4c…`、`CommandHandler.class` `0828479e…`、
  `ConsumerGroup.class` `afba9667…`）量得。**翻 14 行**：`:7 :14 :15 :16 :17 :18 :20 :24 :25 :27 :28 :29 :31 :37`；
  其余 25 行逐字节未动（这一列是"没被波及"的对照，不是"没看"）。两侧各 `wrote=39 lost=none`
  记在 `b59_replay.log`。
- 量具 `code_mut.py` 从 25 支涨到 **38 支**（新增 XADD/XTRIM 那一族 R1-R13，`python3 code_mut.py R`
  分族跑）。分族不换快照文件，所以 R 族打的仍是上一轮工作树那份（`7f335e12…`），
  13 支的还原行逐支回读都对得上。
- 本轮再涨到 **52 支 / 53 个锚点**（新增 XGROUP 那一族 S1-S14，`python3 code_mut.py S` 分族跑；
  量具第一次有了第二个目标文件 —— `stream/ConsumerGroup.java`，因为 DELCONSUMER 那条"回条数"
  的语义长在 `destroyConsumer` 里，只在命令层打桩抓不到）。快照记的是本轮工作树的
  `CommandHandler.java` `2529fd07…` 与 `ConsumerGroup.java` `91296cfc…`；14 支跑完后
  `code_snapshot/*.orig` 与工作树逐个 md5 对账，两个文件都 `SAME`（即一条变异都没残留）。
  加 S 族时 `anchors` 当场 FAIL：P2（XREADGROUP 的 NOGROUP）那 1 行锚点被我新写的 :1852 那一句
  撞成了两处 —— 上游两处本就是同一句话，把 P2 的锚点扩到含 `// :2562 …` 那两行注释才唯一。
  **这次 FAIL 是量具自己先红，不是被测代码红**，且在建任何 jar 之前就被抓住。
- 新公开面两处：`ConsumerGroup.setLastDelivered(ms, seq)`（SETID 与 XREADGROUP 推进位置共用的
  那一步，两段一起换）与 `CommandHandler.helpStatusArray(...)`（`addReplyHelp` 的线形，
  `DEBUG HELP` 与 `XGROUP HELP` 共用一个渲染器）。
- 本轮的电池 `battery60.txt` 33 行（`:1` PING、`:2-:5` 建三条目的流与一个组、`:6-:16` SETID 的
  写读交替（`2-2` / `$` / `-` / `+` / `5` / `bad-id`）、`:17-:21` 组不在 / 键不在 / arity 三格、
  `:22` 与 `:27 :28 :29` 拿 `CREATE` 的严格解析和 String 键当对照、`:23` 有效写、
  `:24-:26` HELP 三格、`:30-:33` 收尾核副作用）。三侧各 `wrote=33 lost=none`：
  改前 `battery60.pre` 由 `jar_setid_pre`（整包 `ebb0ce4c…`、`CommandHandler.class` `0828479e…`
  = HEAD `26e0daa` 那棵树）量得；补分派后 `battery60.post` 由 `jar_setid_post`（整包 `e910cff9…`、
  CH `c08cfcd6…`、`ConsumerGroup.class` `282370ec…`）量得；修顶格比较后 `battery60.cgfix`
  由 `jar_cgfix`（整包 `4e08f52f…`、CH 同一枚 `c08cfcd6…`、CG `be8d543f…`）量得 ——
  两枚 jar 的 CH 相同是因为那一步只动了 `ConsumerGroup`。
  提交前又用**提交树重建**的那枚（`jar_committed`，整包 `c0953be0…`、CH `26e5ff49…`、
  CG `be8d543f…`）把三侧各重放了一遍：读数与 `battery60.cgfix` / `battery61.cgfix` /
  `battery62.post` **逐行相同**（三行各 `wrote=33 / 13 / 10`、`lost=none`）。
  CH 的 class md5 会漂是因为后面只加了注释（行号表跟着动），行为字节没变 ——
  这正是"整包 md5 只作参照、身份看 class、而 class 相同也不等于源码相同"的那一条。
- `battery61.txt` 13 行是**为了给一个病定年代而写的**：四个组分别起步于 `MAX-MAX` / `2^63-0` /
  `0-0` / `1-MAX`，每个组后面紧跟一次 `>` 读。它在 `jar_setid_pre` 与 `jar_setid_post` 上
  **逐行相同**（`battery61.pre` 与 `battery61.post` diff 为空），因此"顶格位置读起来像流起点"
  不是 SETID 带进来的；修完才翻 4 行。`battery62.txt` 10 行是 XPENDING 摘要那两处形状的改前面，
  本轮闭上：那一轮另写 `battery63.txt` 10 行，改前后读数与成因见上面
  `#### XPENDING 摘要的第 4 项` 那一节。
- `code_mut.py` 涨到 **61 支 / 62 个锚点**（新增 T1-T9，`python3 code_mut.py T` 分族跑）。
  T 族的选取器必须同时带上新方法与对岸那一侧的 `debugSubcommandGrammarMatchesTheReference`，
  因为 `helpStatusArray` 是两个 HELP 共用的渲染器 —— 只跑 XGROUP 那侧等于没量 DEBUG 那侧的读者。
  脚本的"族名前缀"判断里原本没有 `T`，不加 `code_mut.py T` 会被当成一支未知探针而 FATAL。
  快照换成本轮工作树的 `CommandHandler.java` `78c90f1a…` 与 `ConsumerGroup.java` `93c86d99…`，
  9 支逐支回读还原 md5 与副本相同（T8 那一支改写过一次，见上面那一节的记录）。
- 全量反应堆：**877 例全绿，连跑两遍**（`358 + 383 + 134 + 2`，四个模块各自 0 失败 0 错 0 跳过，
  `b60_full.log` 与 `b60_full2.log` 尾都是 `BUILD SUCCESS`），core 从上一轮的 382 抬到 383
  （本轮新增一条协议用例）。第二遍是在测试文件又加了一格（`XGROUP HELP <不在的键>`）之后跑的。
- 最新这一轮的电池 `battery63.txt` 10 行（`:1` PING、`:2 :3` 建流与组、`:4` 空 PEL 的汇总、
  `:5` c1 读走一条、`:6` **`XGROUP CREATECONSUMER` 现造一个 0 条的消费者**、`:7` 非空 PEL 的汇总、
  `:8` XINFO CONSUMERS（同一个现场的反向对照：c2 必须仍在且报 `pending, :0`）、`:9` XACK、
  `:10` 回到空 PEL）：改前 `battery63.pre` 由按 HEAD 内容重建的 `b63_pre.jar` 量得，改后由工作树
  重建的 jar 量了三遍（`battery63.post` / `.post2` / `.post3`）。**翻 3 行（`:4 :7 :10`）**，
  `:8` 只差 `idle`（现量时间不是行为；`post` 与 `post3` 逐行相同、`post2` 与 `post3` 也只差那一格）。
  两侧各再重放一遍留磁盘证据：`battery63.pre2` 由 `b63_pre.jar` 重放（`b63_replay_pre.log`
  `wrote=10 lost=none`），`battery63.post3` 由 `b63_post3.jar` 重放（`b63_replay4.log` 同）——
  同一枚 jar 两遍各自只差 `:8` 的 `idle`（改前侧 `pre` 与 `pre2` 也只有那一行不同），
  而 `pre2` 与 `post3` 之间翻的正是那 3 行。
- 一份要写进制度的构建坑：**z-cache 的树搬到 monorepo 之外就编不出能跑的包**。根 pom 的
  `<parent> com.zifang:z-opc:1.0.0-SNAPSHOT` 带 `<relativePath>../pom.xml</relativePath>`，
  出了 monorepo 那个父 pom 解析不到、静默退到 m2 里的旧构件，于是 shaded jar 塞的是
  `z-cache-common:jar:1.3.1`（里面根本没有 `StreamIdFormat` / `RespFrameReader`），服务器起不来、
  重放只报 `NO PONG`。`git archive HEAD` 的导出树和普通目录拷贝都踩这一条，而且两次都伪装成
  "被测的东西坏了"。所以改前面那枚 jar 只能在**树内**用 `git show HEAD:` 覆盖那两个文件、
  建完按 md5 还原（工具 `b63_prestate.sh`，收尾打印两侧 md5 与 `git status`）。
  重放用的 jar 从此一律过两道新鲜度闸：构建日志含 `Including io.github.yuku123:z-cache-common:jar:1.3.6`、
  包内有 `com/zifang/z/cache/common/protocol/StreamIdFormat.class`（本轮实测 12 个 common class 条目）。
- 三枚 jar 的身份（class 级 md5；整包 md5 只作参照）：`b63_pre.jar` CH `26e5ff49…` /
  CG `be8d543f…`（= HEAD 那棵树）、`b63_post.jar` CH `6b67d9d2…` / CG `56f3d0bb…`、
  `b63_post3.jar` CH `15bb4380…` / CG `1e66b6d6…`（注释按 U4/U7 的实测改写之后）。
  三枚的线上读数只差 `idle` —— "行号表让 class md5 漂、行为字节没漂"那条在这里第三次复现。
- `code_mut.py` 涨到 **68 支 / 69 个锚点**（新增 U1-U7，`python3 code_mut.py U` 分族跑；
  族名前缀的判断里加了 `U`）。U 族的选取器 `XPEN` 必须连 `xinfoConsumersRepliesWithConsumerRows`
  与 `streamFamilyHoldsTheSameOneTypeInvariant` 一起带上：U4/U7 打的是 XINFO 那一侧的读数来源，
  只跑 XPENDING 那两个方法等于没量它。
  **U7 最初写成了 U4 的逐字节副本**（同文件、同锚点串、同替换），也就是"68 支"里有一支是空跑的 ——
  把两支的定义串摆在一起才看出来。重写成反方向那一支（摘掉 XINFO 的 `getOrDefault` 兜底）后
  单独打 SURVIVED（与 U4 同族：种子行保证键必在），再手打一次两支合并确认它会炸
  （`b63_U4U7_combined.log`：两条 `Cannot invoke "java.lang.Long.intValue()" because … get(…) is null`，
  4 例红 2 例）。合并那一次是手工改的，收口从 `code_snapshot/*.orig` 用 `cp` 还原并核 md5。
- 快照换成本轮工作树的 `CommandHandler.java` `6d7574cd…` 与 `ConsumerGroup.java` `8ee71f91…`；
  U 族 7 支逐支回读，还原行都写着"与副本逐字节同"。
- 全量反应堆：**878 例全绿**（`358 + 384 + 134 + 2`，四个模块各自 0 失败 0 错 0 跳过，
  `b63_full.log` / `b63_full2.log` 尾都 `BUILD SUCCESS`），core 从上一轮的 383 抬到 384
  （本轮新增一条协议用例）。第二遍 `b63_full2.log` 是在注释按 U4/U7 实测改写之后跑的。
- `StreamStore.xreadgroupNew(...)` 多了一个 `noack` 形参、`ConsumerGroup.markDelivered(...)` 也是
  （两个口子都只被命令层与 `StreamTest` 用，没有对外行为变化）；命令层新增共用的
  `scanXreadOptions(...)` 与读数载体 `XreadSpec`（XREAD / XREADGROUP 从此走同一圈选项扫描）。
- 最新这一轮的电池 `battery64.txt` 24 行（`:1-:4` 建流、组与一枚 String 键、`:5-:8` 配对那一问
  含它那道 arity 控制与"混着 String 键也报配对"那格、`:9-:12` 两句误用句的三种摆法、
  `:13-:16` XREADGROUP 的 -7 闸与 XREAD 的 -4 闸各两格、`:17-:20` NOACK 投递 → `XPENDING` →
  再读一次 → 账仍是空、`:21` 副作用对照、`:22-:24` "Missing GROUP" 与两句判序控制）：
  改前 `battery64.pre` 由**提交树 `adf6354` 重建**的 `b64_pre.jar` 量得（工作树当时与 HEAD 逐文件相同，
  这一轮的"改前面"不需要 `git show` 那套覆盖），改后 `battery64.post` 由 `b64_post.jar` 量得。
  **翻 12 行（`:6 :7 :8 :9 :10 :11 :12 :17 :19 :20 :22 :23`）**，其余 12 行逐字节未动
  （`:5 :13 :14 :15 :16 :24` 正是那六道闸与判序控制，它们本来就没打算翻）。
  两侧各 `wrote=24 lost=none`（`b64_replay_pre.log` / `b64_replay_post.log`），
  CH 的 class md5 从 `15bb4380…`（提交树）到 `8edaa85f…`（本轮）。
- `code_mut.py` 涨到 **76 支 / 77 个锚点**（新增 V1-V8，`python3 code_mut.py V` 分族跑；
  族名前缀的判断里加了 `V`，量具第一次有**第三个**目标文件 `stream/StreamStore.java`，
  因为 V8 要打的是"投递前顺手建消费者"那一行）。**7 KILLED / 1 SURVIVED**（V8 见上面那一节，
  等价变异）；V4 的红是一句 `internal error: Cannot invoke "Object.hashCode()" because "key" is null`
  —— 摘掉"缺 GROUP 就报那句"之后，`group` 一路走到取组的位置上被当 map 键用，
  这说明那一问不只是文案，它下面真的接不住 null。V5 是纯粹换判序（把两问互换），
  红在 `:24` 那一格：那一行两个问都会答，只有顺序对了才只答一句。
  选取器带上新方法、对岸的 `xreadPositionsAreExclusiveAndHistoryComesFromTheConsumerPel`
  与整个 `StreamTest`（V6/V7 打的是 `markDelivered`，纯函数那一侧也得跑）。
  8 支逐支回读，三个文件还原后与工作树逐个 md5 对账（`79d29d23…` / `2d12be43…` / `3113b87e…`）。
- 全量反应堆：**879 例全绿，连跑两遍**（`358 + 385 + 134 + 2`，四个模块各自 0 失败 0 错 0 跳过，
  `b64_full3.log` 与 `b64_full4.log` 尾都是 `BUILD SUCCESS`），core 从 384 抬到 385（新增一条协议用例）。
  这两遍是**含上面那处探针修改**之后跑的。中间那一遍红的（`b64_full2.log`，两例 `HTTP/1.1 400`）
  不是服务器行为退步，已按三条取证 + 注入现场 A/B 归到量具上，见上面那一节 —— 台账里留着它，
  是因为"同一棵树几分钟前全绿、中间只改了注释"这种红，最容易被人直接抹成"环境问题"而不留证据。
- `ConsumerGroup.NAME_ORDER`（public 的码点序判据）+ `CommandHandler.sortedNames(...)`：
  XINFO GROUPS / XINFO CONSUMERS 两支的行序、`perConsumerPending()` 的那份 `TreeMap`
  从此共用一个判据，上游依据行号（:2568 / :2594 / :2079-2089）与"为什么不是
  `String.compareTo`"都写在那一段注释里。
- 新协议用例 `RedisServerProtocolSemanticsTest.xinfoGroupsRowsCarryTheGroupPositionAndComeOutInNameOrder`：
  GROUPS 的 8 元素行逐行断言（含 `top` 那行的顶格无符号文本、`zeta` 那行 SETID 之后的 `7-7`）、
  无组时 `*0`、CONSUMERS 逐元素（`*6` 一行、`$pending` 后跟 integer、`idle` 只读不钉值）、
  码点 prey 两处（`xi:u` 的 GROUPS 行序、`xi:p` 的 XPENDING 汇总行序）、以及换名之后的
  `xi:c` 那三行消费者（`c1:0 / c2:1 / c3:1`，"0 条的也要列"仍在这一格里）。
- 最新这一轮的电池 `battery65.txt` **36 行**：`:2 :3 :4` 建流、`:5-:9` GROUPS 的插入序 vs 升序、
  `:10-:15` 两个消费者与投递后的两张表、`:16` XACK、`:17-:18` SETID 之后回读位点、
  `:19-:26` 一枚只有一个组的流（含 `XREADGROUP` 顺手建消费者那一格）、
  `:27-:28` 顶格位点、`:29-:36` 换名之后的 CONSUMERS 行序 prey（含 `XPENDING` 汇总的对照）。
  改前由 `b65_pre.jar` 量得（class 级 md5：`CommandHandler` `8edaa85f…` / `ConsumerGroup`
  `ba36351d…`，前者与上一轮记在 `battery64` 那一条里的 `99af145` 那枚 class md5 相同）；
  改后由 `b65_post.jar` 量得（`5a32d6b1…` / `8717c9b8…`，本轮工作树）；两侧各 `wrote=36 lost=none`
  （`b65_replay_pre_rows36.log` / `b65_replay_post_rows36.log`），先各留了一份 28 行的旧账
  （`battery65.pre.rows28` / `.post.rows28`）好把"新加的行"与"改动的行"分开算。
  `zdiff.py` 的原始账是**真实不一致 8 行 + 顺序不同 1 行**；那 8 行里 `:12 :25` 只差 `idle`
  （按噪声记账），剩下 `:9 :14 :18 :22 :24 :28` 六行加那行"顺序不同"的 `:35` 才是本增量的证据。
- `code_mut.py` 涨到 **83 支 / 84 个锚点**（新增 W1-W7，`python3 code_mut.py W` 分族跑；
  族名前缀的判断里加了 `W`）。**7 支全 KILLED**，红的句子逐支不同（见上面那一节的自证清单）。
  U7 那一支的锚点因为 `entry.getKey()` → `cname` 这一次改名而落空，已同步改掉两处锚串
  —— 改名会牵动锚点，这是量具该跟着走的形状，不是探针的错。
- 量具多一道自证：`code_mut.py selectors` 把 12 个选取器里点名的每个测试方法拿测试类源码逐个
  回读，认不出就 FATAL（surefire 对 `-Dtest=Class#不存在的方法` 不报错，只是那一条不跑）。
  它的牙是注入一个假名验的：当场 4 条 FATAL 点名到探针。**顺带查出既有 XOPT 里同一个错名**，
  V 族八支此前每支在那个类里只跑一条用例（换名 A/B 见 `b65_selector_ab.log`：
  假名 `Tests run: 1`、真名 `Tests run: 2`，两边都 `BUILD SUCCESS`），
  改正后 V1 重跑是 `Tests run: 23`、仍 KILLED。
- 全量反应堆：**880 例全绿，连跑两遍**（`358 + 386 + 134 + 2`，四个模块各自 0 失败 0 错 0 跳过，
  `b65_full2.log` 与 `b65_full3.log` 尾都是 `BUILD SUCCESS` / `full_rc=0`），core 从 385 抬到 386
  （本轮新增那条协议用例）。跑 W 族之前先把快照换成本轮工作树的 `CommandHandler.java`
  `bc555595…` / `ConsumerGroup.java` `12d8ca22…` / `StreamStore.java` `3113b87e…`
  —— 那份 03:39 的旧快照装的是 XINFO 之前的字节，拿它还原会连本轮未提交的改动一起抹掉。
- `StreamStore.xadd` 的 `maxLen` 形参改成三态（**注释钉死 -1 = 没给、0..n = 裁剪到 n**），
  命令层 `handleXadd` 的默认值随之从 `0` 换成 `-1`；`StreamTest` 里那 7 处把 0 当"不裁剪"传的
  调用点（`:187 :195 :196 :205 :206 :233 :234`）逐处读过它本意是"没给"才改成 -1 ——
  改之前先看它在哪一个测试方法里，不是把 7 处无脑换掉。
- `RedisServerProtocolSemanticsTest#xaddMaxlenZeroClearsTheStreamAfterReplying`：0 先交 ID
  再清空、自动 ID 那一支同样不例外、清空不删键也不动表顶（`XADD k MAXLEN 0 5-5 a 1` 之后
  `XADD k 5-5 a 2` 仍吃"等于或小于表顶"）、`XTRIM … MAXLEN 0` 与负数那一句各是对照组。
  `StreamTest#testStreamStore_MaxLenMinusOneIsNotGivenWhileZeroClears` 是 java 层的同一格。
- 电池 `battery66.txt` **30 行**：`:2-:7` 三枚种子后 `MAXLEN 0`（改前翻 `:6 :7`）、`:8-:10`
  `MAXLEN 2` 对照组、`:11-:14` `XTRIM MAXLEN 0`（改前就是通的）、`:15 :16` 首条即 `MAXLEN 3`、
  `:17` `MAXLEN -1` 那一句原文、`:18-:21` 自动 ID 那一支（`:19` 是时间噪声）、`:22-:25`
  连砍两刀、`:26-:30` "清空不等于删键"那一格（`:28` 两侧逐字相同，是这一支的对照组）。
  改前 `battery66.pre` 由提交树 `1f55599` 重建的 `b66_pre.jar` 量得，改后 `battery66.post`
  由 `b66_post.jar` 量得，两侧各 `wrote=30 lost=none`。
- `code_mut.py` 涨到 **87 支 / 88 个锚点**（新增 X1-X4，族名前缀判断里加了 `X`），
  `TARGETS` 第一次带第四个文件（`Stream.java`：`trim` 里那两个哨兵都在它身上）。
  **4 支全 KILLED**，红的句子逐支不同（见上面那一节的自证清单）。
- 全量反应堆：**882 例全绿，连跑两遍**（`358 + 388 + 134 + 2`，core 从 386 抬到 388 =
  本轮那两条新用例；`b66_full1.log` 与 `b66_full2_maxlen.log` 尾都是 `BUILD SUCCESS`）。
- `CommandHandler.handleXsetid`（`XSETID key <id>`）：按 `t_stream.c:1931-1956` 的六步装齐 ——
  `server.c:321` 的精确 arity、`:1932` 的"键不在答 `-ERR no such key` 而**不建流**"、
  `:1933` 的类型闸、`:1937` 的 strict 解析（missing_seq 为 0）、`:1942-1951` 那道套着
  `if (s->length > 0)` 且比的是存活最大 ID 的表顶闸（条件 `< 0`，等于存活表顶允许）、
  `:1952-1953` 写表顶并答 `+OK`。`XGROUP` 的分派表里多出一行 `case "XSETID"`。
- `Stream.setLastId(ms, seq)`：把 `last_id` 那两个计数器写成一个新值，**允许往回写**
  （上游那道闸只在有活条目时才拦，空流重开 ID 空间就是这一支的用途）。它与 `trim` 的
  关系由 Y8 变异钉住：把 `setLastId` 改成"只许往上涨"会红在"退回去之后 2-2 就又能写了"。
- `RedisServerProtocolSemanticsTest#xsetidMovesTheTopAndOnlyRefusesToCrossALiveEntry`：
  六段各钉一格 —— 挪表顶之后 XADD 判的是新位置、等于存活表顶不拒而 XADD 拒、空流可以退回
  （连 `0-0` 都可以）而有活条目时 `0-0` 立刻被拒、`-`/`+` 在 strict 侧非法而在
  `XGROUP SETID` 侧合法（这一对是 Y2 唯一抓得住的形状）、裸 `4` 落 `4-0` 不是 `4-MAX`、
  arity 多一个字都不收、以及"取键两问排在 ID 解析之前"那个顺序（`XSETID k67:str bad-id`
  答 WRONGTYPE 而不答 invalid stream ID）。
- 电池 `battery67.txt` **43 行**：`:2-:12` 挪顶与退回的主干（`:5 :12` 是下游证据）、
  `:13-:15` 两键并存与"键不在"、`:16 :17` XCLAIM/XAUTOCLAIM（前者真缺口、后者本来就该
  unknown，两侧同答的两种含义）、`:18-:25` 等于表顶 / `$` / `*` / `0-0` 与它们带红的
  `XLEN`（`:24` 两侧同答，是"被拒的 XADD 连裁剪都不跑"那一格的对照）、`:26-:30`  arity 与判序、`:31-:37` "清空之后仍可退回"那一组（`:31 :32 :36` 是
  对照组）、`:38-:43` strict 判别组（`:40 :41` 是非严格侧的对照）。
  改前 `battery67.pre` 由提交树 `1ea4260` 重建的 `b67_pre.jar`（CH `49603ae4…`）量得，
  改后 `battery67.post` 由 `b67_post.jar`（CH `d10af081…`）量得，两侧各 `wrote=43 lost=none`。
- `code_mut.py` 涨到 **95 支 / 96 个锚点**（新增 Y1-Y8，族名前缀判断里加了 `Y`）。
  **8 支全 KILLED**；Y2 第一次是 SURVIVED，补了 `-`/`+` 那对判别输入之后才红（记在上面那一节）。
- 量具侧一件永久的事：5.0.14 的**完整源码**解到 `~/.cache/zcache_gauges/full5/`，
  命令表从此本机可读（`server.c:314-327`）。stream 族九支的 arity 全部现抄：
  `xadd -5`、`xlen 2`、`xgroup -2`、`xsetid 3`、`xack -4`、`xpending -3`、`xclaim -6`、
  `xinfo -2`、`xdel -3`、`xtrim -2`，而 **xautoclaim 不在表里**。
- 全量反应堆：**883 例全绿，连跑两遍**（`358 + 389 + 134 + 2`，core 从 388 抬到 389 =
  本轮那一条新用例；`b67_full1.log` / `b67_full2.log` 尾都是 `BUILD SUCCESS`）。
- `MemoryStore` 从此认识第六种键：`DataType.STREAM`、`typeOfDb` 会问 `StreamStore`
  （`:220`），`bindStreams` / `hasStreams`（`:229`）/ `streamKeys`（`:235`）三个新缺口，
  `keysDb` / `scan` / `dbsizeDb` / `flushDb` / `moveKeyToDb` / `clearOtherTypes` 六处读者
  一起跟上。**注入是可选的**：没接上注入的 `MemoryStore` 对 stream 一律答"没有这一枚键"，
  而不是猜一个数。
- `StreamStore` 添三个键空间用的门面：`keySet(db)`（只读视图，供 `KEYS`/`DBSIZE`/`SCAN` 数）、
  `rename(db, src, dst)`、`moveTo(fromDb, toDb, key)` —— 后两支搬的都是 `Stream` 对象本身，
  所以表顶、消费组、PEL 跟着走。`RedisServer` 在构造里把 `ServerScope` 那一份流表注入
  `store`（构造点全仓唯一：`grep -rn "ServerScope(" --include='*.java' .` 实测只有
  `RedisServer.java:110-111` 那一处，写的是全限定名 `new com.zifang.z.cache.core.command.ServerScope(…)`
  —— 拿 `new ServerScope` 这四个字去 grep 是 0 命中，别据此以为我编了）。
- `CommandHandler`：`deleteEveryType` 的第六路、`handleRename` 的 `case STREAM`、
  `streamTypeConflict` 放行 `STREAM`；`handleRandomkey` 不再在 `keysDb` 之外自己并一遍
  另外四张表。
- `RedisServerProtocolSemanticsTest.streamsAreOrdinaryKeysForKeyspaceCommands`（该类第 45 例）：
  六张表各摆一枚后 `DBSIZE :6`、六种 `TYPE` 与 `EXISTS` 逐一对号、`KEYS kt:*` 与
  `SCAN 0 COUNT 100` 各交出六枚、`DEL` 六枚回 `:6` 之后 `DBSIZE :0`；再钉 `RANDOMKEY`
  抽得到流键、`SET` 顶掉流键之后 `DBSIZE` 只算一格、`RENAME` 带着消费组与 PEL 一起走
  （`XINFO GROUPS` / `XPENDING` / `XACK` 三问都落在新键名上）、`MOVE` 之后两边各问一遍、
  `FLUSHDB` 之后 `DBSIZE` / `KEYS` / `TYPE` 归零而 `XADD` 能从 `1-1` 重写，末尾一段是
  TTL 那三行的**现状钉桩**（含一枚 list 键的对照）。
- `MemoryStoreTest.streamTableIsBlindUntilItIsBound`（该类第 40 例）：注入前的形状
  （`NONE` / `dbsizeDb 0` / `keysDb` 空，且 `clearOtherTypes` 与 `flushDb` 都不许动那张表）
  与注入后的形状（`STREAM` / `dbsizeDb 1` / `keysDb` 点名）各钉一遍，`bindStreams(null)`
  再退回前者。
- 量具：`code_mut.py` 新增 Z 族 15 支（键空间的每一个读者一支，另有 Z10 单打"接线"），
  到 110 支探针 / 111 个锚点；`battery68.txt` 从 39 行扩到 83 行，两侧对拍
  `真实不一致=30`、`顺序不同=0`。
- 全量反应堆：整个增量期间**跑过 9 遍**（`358 + 391 + 134 + 2 = 885`，core 从 389 抬到 391 =
  本轮两条新用例）。**7 遍全绿**（`logs/b68_full1/2/3/5/6/7/9.log`），**2 遍红**
  （`b68_full4.log`、`b68_full8.log`）。分账要分清：`1`、`2` 两遍是改注释之前的同一棵代码树，
  `3` 之后所有判据字节就定了，最后那四处行号注释改完之后又跑了 `5/6/7/8/9` 五遍
  —— **提交字节被量过 5 遍：4 绿 1 红**。两遍红是**同一个既有抖动**、都与本轮改动无关：
  `RedisServerLifecycleTest` 的
  `startAndWait:765` 抛 `IllegalStateException: server thread died before listening on
  <port>` + `BindException: Address already in use`，而红的用例两遍不同（第 4 遍
  `saveWithoutDataDirFailsHonestly:478`、第 8 遍 `blpopPopsOnlyRequestedKeyWithoutFreezingPeers:123`）。
  取证：`lsof -nP -iTCP:64088` 在红过之后显示那枚端口挂在 `verge-mih` 的一条 `FIN_WAIT_2`
  **出站**连接上 —— `freePort()` 的写法是"bind(0) 探一个端口、关掉、再交给服务器去 bind"，
  中间那道空隙里操作系统会把同一个号码派给一条新的出站连接（本机常驻代理，出站端口消耗快）。
  9 遍里中 2 遍 ≈ 两成（按"提交字节那 5 遍"算是 1/5），**这一条另立一票修**（测试侧，四个类里各有一份逐字相同的
  `freePort()`），不许读成"本轮改坏了"。**那一票已经跑完并闭合**：见上面《测试取号压在操作系统出站区间之下》
  那一格（改后 12 遍全绿、基线抬到 886）；同一来路还剩的 `ZCachePoolTest` 那一处也已闭合，
  见《让 listen socket 自己挑号码》（基线再抬到 887）。
  上一条 `883` 的读数是上一轮那棵树的，留着记账。
- 变异自证与提交树的字节对账：Z 族那 15 支跑完之后，四本 TARGET 又各量一次 md5，与快照
  逐字相同（CH `bde83a2252efb01f73d8fa7ed8703e93`、MS `35bb2d879b816b74607d3ad7af9a2ec7`、
  SS `832816457d9567fa19f324193174709d`、RS `13ae0ecda5a537be2f3305d24c2c6fd6`），
  且跑完全量再量一次仍是这四个值 —— 也就是"红过的那 15 支，打的就是要提交的这一版字节"。
  两支测试文件在 Z 族之后只改过四处**注释/断言消息里的行号**（`第 41→46 行`、
  `第 51→56 行`、`db.c:831→830` 两处），判据一条没动。
- 取号一族的最后一处也拔掉了（`ZCachePoolTest`，见上面《让 listen socket 自己挑号码》那一格）：
  `b.bind(0)` + 从 listen socket 读回号码，第五份 `new ServerSocket(0)` 和 `testPort = 16379`
  的退路一起删；新增守卫 `advertisedPortIsHeldByOurOwnListeningSocket`（该类 22 例 → 23 例）。
  全量**连跑 7 遍 7 绿**，基线 **886 → 887**（`358 + 392 + 135 + 2`），7 份日志里
  `Address already in use` 合计 0 命中（`logs/zpt_full1.log` … `zpt_full7.log`）；
  注释微调之后按提交的字节再跑 2 遍（`logs/zpt_final2.log`、`zpt_final3.log`，同 887 同 0 命中）。
  两支注入各打中一问（`logs/zpt_mutA.log`、`logs/zpt_mutB.log`），还原按字节 `cp` 并 md5 对账
  `afc41ece8c09ba1df3b2babef3a9bf81`。

### 已知边界（这一版没动，说清楚）
- RESP3 / `HELLO`、`EVAL` / `EVALSHA` / `SCRIPT` 依旧没有服务端实现，客户端 `DistributedLock`
  因此仍走"GET 校验后 DEL"的非原子路径。
- Stream 不参与 RDB/AOF；只有 String 键的 TTL 进快照。**集合键（含 stream）连 `EXPIRE`
  都还不支持** —— 上游那一问 `expireGenericCommand`（`expire.c:415-451`）只有 `:426`
  的 `lookupKeyWrite` 一道闸、没有类型分支，所以它回 `:1` 并真的挂上过期，我们回 `:0`；
  因此 `RENAME` 至今只搬得动 String 的 TTL（**流对象本身现在搬得动了**：表顶、消费组、
  PEL 跟着指针走，见上面《stream 键是键》那一节，缺的只有 TTL 这一栏）。
  1.3.6 里"时刻搬到键空间上"那一格只做了存储那一半（`MemoryStore.expirations` 已经与类型无关，
  读口是 `expireAtDb` / `hasExpirationDb` / `isExpiredDb`），**上面这些行为一条都没变**：
  还缺的是命令层那五支去问 `typeOfDb`、四个集合 store 的 `del` 各补一句回收、以及
  `getAllExpirationEntries` 之外的逐类型存档路径（现在只有 String 的时刻进得了快照）。
- Stream 这一族的**文法**、**XADD 的单调性**、**XREAD / XREADGROUP 的位置语义**、
  **取键那一问的类型闸门**、**错误码与 XREADGROUP 的 NOGROUP 那一问**这一版都收了。
  剩下的读侧边界全在**答复层**（文案与形状），下面每一条都标清依据档次：
  要么是本机现状实测的行号，要么是上游 `t_stream.c` 的行号，两者都不写的就是没量过 ——
  不许照抄成事实。
  - **`Stream.maxLen` 是这一格留下的死账（实测：全仓 0 读者）**：`Stream.trim` 每次把传进来的
    值写进字段（`Stream.java:136`）并配一个 `getMaxLen()`（:219），而这两个都没有调用者 ——
    grep 实测只有定义自身那一行。上游那一侧：`t_stream.c` 里 `max_len` 0 命中，
    `XADD` 的裁剪是把参数当场交给 `streamTrimByLength`（:1329），没有任何"这条流以后都裁到 n"
    的状态。所以这一格的正确收口是**删掉那个字段和那个 getter**，不是给它找读者
    （"MAXLEN 是不是持久上限"在别的版本里另说，不在这一次的权威（本机只有 5.0.14 的
    `t_stream.c`/`server.c`/`object.c`/`networking.c`/`version.h` 五个文件，`server.h` 不在其中，
    所以 `struct stream` 的字段表我读不到）范围内）。本轮没删：它改前改后都不参与任何答复，
    不属于这一支的偏差面。
  - **一条更正记录（不是待改项）**：更早的版本在这里写过"`XACK` 对不存在的组回 `:0`，
    上游那一句同样是 `-NOGROUP`"，行号记成 `battery53:26` —— **那一行不存在**（那一版 25 行），
    那句"上游同样是"我也没读过源码。读了 :1967-1980 的结论正相反：
    `No key or group? Nothing to ack`，键或组不在都回 `:0`，所以我们的 `:0` 本来就对
    （`battery54:23`、`:35`，这一版 `battery56:14` 再量一次仍是 `:0`）。
  - ~~**键空间看不见 stream（本机现状实测，`battery54:41-46`）**：`TYPE t53:ok` 回 `+none`、
    `EXISTS` 回 `:0`、`DBSIZE` 回 `:2`（那两枚是 `t53:l`/`t53:h`）、`DEL t53:ok` 回 `:0`
    而 `XLEN` 仍回 `:1` 且还能继续 `XADD`（"同一个键名两种视图"的另一半）。~~
    —— **本轮已闭**（见上面《stream 键是键》那一节：`battery68` 83 行两侧对拍，实测翻 30 行，
    Z 族 15 支全 KILLED）。`battery54:41-46` 那一份读数留在原处，是为了记改前的形状从哪量来的。
    **闭的只是"看得见"**，两个后继缺口各自还开着：① **TTL 一族仍不问第六张表**
    （`expire.c:426` 只有一道 `lookupKeyWrite`、无类型分支 ⇒ 上游 `EXPIRE` 在流键上回 `:1`，
    我们回 `:0`；这一条与上面集合键那条同源，用例末尾按现状钉桩并带一枚 list 键对照）；
    ② **`RANDOMKEY` 的抽样权重没有量具**（本轮删掉了 `handleRandomkey` 里 `keysDb` 之外的
    第二次枚举，那四种键原先在随机池里被数两次、抽中概率翻倍；抽样是随机的、
    `ThreadLocalRandom` 无可注入接缝，83 行对拍对"权重"结构上无感 —— 见上面那一节的
    "未覆盖"一段）。
  - **答复层剩余项（本机现状已逐条实测：`battery55` 46 行 / `battery56` 15 行，
    改前改后各一份；下面每行左边是我们答的原文，右边是上游行号）**：
    - ~~`XRANGE t55:ok - + COUNT 0` 与 `XREVRANGE … COUNT 0` 交出整表~~ —— **本轮已闭**
      （见上面《XRANGE 的 `COUNT` 有三种答复形状》，`battery57` 27 行实测翻 9 行）。
      那一处 `battery55:5 :7` 的读数留在这里，是为了记改前形状从哪量来的。
    - ~~`XADD` 的 arity 三行 `battery55:27 :28 :31` 全回 `wrong number of arguments for 'xadd' command`，
      `:29`（`XADD t55:ok 5-5 a`，落单的值）回我们自己的 `XADD needs at least one field value pair`~~
      —— **本轮已闭**（见上面《XADD / XTRIM 的那一圈扫描》，`battery58` 46 行实测翻 16 行）。
      **顺带更正我上一版写在这条里的推导**：原文是"上游 :1284-1286 把这些都归到同一句**裸句**
      `wrong number of arguments for XADD`"，这句不对 —— 读了命令表才分清，`xadd` 的 arity 是
      `-5`（`server.c :314`），四个字的 `XADD k 5-5 a` **进不了函数**，答的是命令表那一形
      `for 'xadd' command`；只有六个字以上（`XADD k 5-5 a 1 b`、`XADD k MAXLEN 2 5-5 a`）
      才轮到 :1284-1286 的裸句。两种形状都存在，分界是字数，不是"哪一句更正版"。
    - ~~`MAXLEN` 非负那一栏 `battery55:30`（XADD）与 `:34`（XTRIM）都回
      `MAXLEN requires a non-negative integer`；上游是 `The MAXLEN argument must be >= 0.`
      （XADD :1268-1270 / XTRIM :2492）~~ —— **本轮已闭**，两处各自换回原文并各有一支变异
      （`code_mut.py R6` / `R7`，两支红的是不同方法里的不同断言）。
      这一句最初是本机 1.3.5 那轮《XADD / XTRIM 的 MAXLEN 参数形状不合 Redis》引进来的，
      本轮把那一轮的四处旧断言一起重钉（`xtrimTakesTheRedisArgumentShape` 的 `:326`、`:332`、`:337`，
      加上 `errorRepliesCannotForgeAnExtraLine` 里那条 XTRIM 载体 `:1698`）—— 旧断言钉的是当时的行为，
      不是上游，留着就会把修好的东西又"修"回去。
    - ~~**`XADD … MAXLEN 0` 与 `XTRIM … MAXLEN 0` 现在语义相反，本轮没动**（本机实测
      `battery58:28 :29`：`XADD t58:cap3 MAXLEN 0 5-6 e 5` 回 `5-6`，紧跟的 `XLEN` 回 `:1`）。~~
      —— **本轮已闭**（见上面《`XADD … MAXLEN 0`：0 是"清空"，而"没给"另有其人》，
      `battery66` 30 行实测原始账 9 行不一致，剥掉一行时间噪声后 8 行是这一格，四支变异 X1-X4
      全 KILLED）。当年这一条里写的修法就是照做的：`xadd` 的形参换成三态、`StreamTest` 那
      7 处逐处判本意（读它所在的测试方法，不是无脑换掉）。**依据档次没变**：我们改前答 `:1`
      是本机实测，"上游答 `:0`"是 :1240/:1268/:1327 的源码推导 —— 参照实例 4.0.9 根本不认
      stream，这一支没有可对拍的真值，所以修完之后它仍然是"按上游源码对齐"而不是"与实例对拍通过"。
    - ~~`XGROUP CREATE t55:nokey g9 0-0` 回 `+OK`（`battery55:14`）；上游 :1837-1845 要求键必须存在
      （除非带 MKSTREAM）~~ —— **本轮已闭**（见上面《XGROUP 的三道闸》，`battery59` 39 行实测翻 14 行，
      `code_mut.py S4` 打的就是那一问）。**MKSTREAM 的判据面补了一层间接证据**：
      `CREATE <不在的键> g 0-0 MKSTREAM` 回 `+OK` 之后，同组再 `CREATE` 必须回 `-BUSYGROUP`
      —— 组挂在这枚流上，说明流对象确实建出来了（`RedisServerProtocolSemanticsTest.java:3095-3098`）。
      当年跟着这一句一起记的"键空间看不见 stream（`TYPE` / `EXISTS` / `DBSIZE` / `DEL` 照旧）"
      **本轮已经闭上**（见上面《stream 键是键》那一节），剩下的只有 `XLEN` 这一问的取证限制：
      它区分不了空流与无键（`battery59:10 :11 :12` 三格全是 `:0`），所以"MKSTREAM 建出了流对象"
      这一问仍只能靠 `-BUSYGROUP` 那条间接证据，不能靠 `XLEN`。
    - 选项句四类**本轮已闭**（见上面《XREAD / XREADGROUP 的选项那一圈》，`battery64` 24 行实测
      翻 12 行）：`Unbalanced XREAD list of streams: …`（:1445-1449）、
      `Missing GROUP option for XREADGROUP`（:1481-1486）、
      `The GROUP option is only supported by XREADGROUP. You called XREAD instead.`（:1453-1458）、
      `The NOACK option …`（:1462-1468）四句都按线上文本钉住了。
      当时那条记法有两处要纠正：一是把 `battery55:20 :21`（`XREADGROUP STREAMS …`、缺 GROUP）
      也算成"该报专有误用句"—— XREADGROUP 的 arity 是 **-7**（server.c:319），那两行在闸外就该
      回 arity 句，改前改后都对；二是漏了判序 —— 缺 GROUP 那一问排在"没见过 STREAMS"之后，
      而 `NOACK` 那一问排在两者之前（新控制格 `battery64:24` 钉的就是这一序）。
      **同一支里仍没做的只剩逐条目形式**（下面那一支）。
    - `XPENDING` 的逐条目形式仍明确拒绝：`battery55:39 :40 :41` 三行都回
      `-ERR XPENDING detail form (IDLE / start / end / count) is not supported`；摘要形式 `:45`
      回 `:1 / "1-1" / "1-1" / [[c55,1]]`。
    - **`XPENDING` 的摘要形式那两处形状不合：本轮已经闭上，留这一行只为把当轮的现场读数与成因
      纠正对齐**（量出来时记的是 `battery62` 10 行，改前现场 `battery62.post`；修的那一轮另写
      `battery63.txt` 10 行，读数见上面 `#### XPENDING 摘要的第 4 项` 那一节）：PEL 空时第 4 项交
      `*-1`（:2059-2062 的 `shared.nullmultibulk`），PEL 非空时手上没货的消费者被跳过（:2086 那句
      `continue`）。当时那句"两个读者要两种形状，共用一个来源就一定有一侧错"是错的，一并纠正：
      XINFO 那一支自己遍历 `getConsumers()`、计数用 `getOrDefault(…, 0L)` 兜，与
      `perConsumerPending()` 的种子行互为备份（两支变异各单打都不红、一起打才炸）。
      **同一支里仍没做的还剩逐条目形式**（上面那一支）。
    - ~~`XSETID` / `XCLAIM` / `XAUTOCLAIM` 三行（`battery55:36 :37 :38`）都回
      `-ERR unknown command '…'`。~~ **本轮把 XSETID 那一行闭了**（上面 `#### XSETID key <id>`
      那一节，`battery67` 43 行两侧对拍）。剩下两行要分开算：`XAUTOCLAIM` 回 unknown 是
      **对的**（5.0.14 命令表 `server.c:314-327` 里没有它，6.2 才加），不该再记成缺口；
      `XCLAIM` 仍是真缺口，而它的 arity 现在可读（`server.c:324` 是 `-6`），卡点只剩
      PEL 的 `delivery_count` / `delivery_time`（下面那一支）。
    - **`XGROUP CREATECONSUMER` 没有权威可比**：`battery55:44` 对已存在的消费者回 `:1`。
      我钉的权威是 5.0.14，而那份源码的 XGROUP 只有 CREATE / SETID / DESTROY / DELCONSUMER / HELP
      （子命令注释 :1794-1797、`help[]` 表 :1799-1805，`DELCONSUMER` 是唯一 `c->argc == 5`
      那一支 :1913），
      **根本没有 `CREATECONSUMER`**（6.2 才加）。所以这一条既不能写成缺陷也不能写成"已对齐"，
      要么换 6.2 的源码当权威，要么把它标成我们自己的扩展。
      本轮把三道闸装好之后，量出来的越界范围收窄了：**只在"键存在"那一支才越界**。
      `battery59:31`（键不存在）现在回"键必须存在"那句，而 5.0.14 走的正是同一条路径
      （闸排在分派之前，压根轮不到报"认不得子命令"），这一行是重合的；
      `battery59:30`（键存在）我们回 `:1`，5.0.14 会回 `Unknown subcommand … 'CREATECONSUMER'`。
      另外那一支"消费者已存在该回 `:0`"（6.2 的语义）我们无条件回 `:1`，
      手头的 5.0.14 判不了它对不对，要动这一条得先把权威换成 6.2 的源码。
  - **CONSUMERS 的行序这一格赌的是哈希表的遍历序，不是结构**（本轮实测：改前那个 jar 在
    `c2`/`c3`/`c1` 上交回 `c3 c1 c2`，W4 因此红）。换 JDK、换 `Consumer` 的哈希实现、甚至换一组
    名字都可能让天然遍历序恰好等于升序 —— 那时断言仍绿而变异会 SURVIVED，也就是**这一格的牙会
    悄悄掉**。要钉死它得让 `ConsumerGroup.consumers` 本身就是一棵有序表（TreeMap + `NAME_ORDER`），
    那样"不排序"这一支就结构上打不出来，代价是每次 `getOrCreateConsumer` 都走比较器；本轮没动，
    因为先要的是"改前测得到、改后答得对"这条证据链，不是把口子换成另一种写法。
  - **`XINFO STREAM` 交回的是 4 个元素（两对：`length` / `groups`，`CommandHandler.java:3188-3193`），
    上游是 14 个（七对）**：`t_stream.c:2612` 写的是 `addReplyMultiBulkLen(c,14)`，
    `:2613-2622` 依次是 `length`、`radix-tree-keys`、`radix-tree-nodes`、`groups`、
    `last-generated-id`，` :2624-2637` 再补 `first-entry`、`last-entry` 那两对
    （走 `streamReplyWithRange`，表空时要 `addReply(c,shared.nullbulk)` 交 `$-1`）。
    这一支没在本轮做，因为它要先决定两件事：`radix-tree-keys` / `radix-tree-nodes` 是 rax 的
    内部量、我们的 `LinkedHashMap` 表示里根本没有对应的数（拿条目数冒充就是假遥测），
    以及 `first-entry` / `last-entry` 要把整条条目嵌套渲染。这两条都得先定口径，不是补字段名。
  - **`delivery_count` / `delivery_time`**：上游每次经 PEL 重交条目都会抬这两个值
    （:1111-1113），`XPENDING` 的逐条目形式与 `XCLAIM` 都读它。我们的 PEL 只有
    `Map<String, String>`（条目 → 消费者），这两个值没有读者，所以这一支不写；
    要把 `XPENDING` 的 IDLE / 次数形式或 `XCLAIM` 做对，得先把 PEL 换成带元数据的结构。
- `XREAD` / `XREADGROUP` 的 `BLOCK` 仍然是明确拒绝而不是实现（`XREAD BLOCK` 回
  `-ERR XREAD BLOCK is not supported…`）——拒绝是有意的：收下 `BLOCK` 等于对客户端谎称会阻塞。
- `XCLAIM` 依旧没有实现（本机实测回 `-ERR unknown command 'XCLAIM'`；arity 是 `server.c:324`
  的 `-6`，卡在没有元数据的 PEL，见上面那一支）。`XSETID` 已在 1.3.6 这一版兑现，
  `XAUTOCLAIM` 在 5.0.14 里根本不存在、不该实现。
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
- 测试端口"先探一个空闲端口再 bind"的窗口：五处里有**四处按号码段规避**（见上面
  《测试取号压在操作系统出站区间之下》那一格：四份 `freePort()` 换进低位窗口），第五处
  （`ZCachePoolTest`）**已连根拔掉**（见《让 listen socket 自己挑号码》：`bind(0)` + 从
  listen socket 读回号码，不再有探针也不再有 16379 退路）。
  **把这一扇窗口的成因量出来的那一次**：
  红的那一格报 `server thread died before listening on 63670 —— BindException: Address already in use`，
  事后 `lsof -nP -iTCP:63670` 抓到占着它的是**本机代理**的一条出向连接
  （`verge-mih … 127.0.0.1:7897->127.0.0.1:63670 (FIN_WAIT_2)`）—— 代理客户端把
  `freePort()` 刚放掉的临时端口拿去当了本地端口，而我们探到的号正好落在
  `net.inet.ip.portrange.first/last = 49152/65535` 这一段里，与它抢的是同一个池。
  同一支两例选取器在**未变异的树**上连跑 8 次：`rc=0` × 8、`BindException` 计数 0（`portflake.log`）。
  还剩的一条：**彻底做法是把 `RedisServer` 也支持 `port 0` 并回读实际端口**，那四份探针就能
  像 `ZCachePoolTest` 一样整段删掉。它现在拒收 0（`RedisServer.java:98-100`），测试侧的构造点
  实测是 **71 处**（`grep -rc "new RedisServer("` 四个测试类 48/20/2/1），不是这里原先估的"约 25 处"。
  低位窗口只保证"探针放掉的号码不会被派给出站连接"，不保证"不会撞上本机另一个常驻服务"
  （本轮 `lsof` 实测窗口里确有一枚被 `*:25170` 占着）—— 撞上就换下一枚，而 `port 0` 是让内核
  自己挑、根本没有那道窗口。

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
