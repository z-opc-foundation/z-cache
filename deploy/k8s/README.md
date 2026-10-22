# z-cache 部署清单（deploy/k8s）

本目录是 **z-cache** 在家庭 k3s 集群（节点 `zifang001` / `zifang002`）的部署清单。

这批 StatefulSet 当初是手工 `kubectl apply` 上去的，仓里查不到「它是怎么部署的」——
集群无法从代码复现，发版只能靠记忆和手记。本目录把**线上实况**（`kubectl get sts,svc,pvc -o yaml` 的真实导出）固化回代码。

> ⚠ 本目录**只产出清单，不代跑**。`kubectl apply` 会改动生产集群，需要人工确认后执行。

## 0. 清单内容

本域是**两个域合一个仓**：官方 `redis` 与自研 `z-cache` 两个缓存实现并排跑，
端口协议一样（6379），但 Service、StatefulSet、数据盘全都独立。

| 文件 | 对象 | 线上对应 |
|---|---|---|
| `00-namespace.yaml` | Namespace `z-cache` | 已存在 |
| `01-service.yaml` | Service `redis`（NodePort 30901）+ Service `z-cache`（NodePort 31001） | 已存在 |
| `02-statefulset-redis.yaml` | StatefulSet `redis`（镜像 `redis:7-alpine`） | 已存在 |
| `03-statefulset-zcache.yaml` | StatefulSet `z-cache`（镜像 `docker.io/library/z-cache:1.3.6`） | 已存在 |
| `04-pvc.yaml` | PVC `z-cache-data`（2Gi / RWO / local-path） | 已存在 |
| `README.md` | 本文件 | — |

线上实况要点（与基线导出逐条对齐）：

**StatefulSet `redis`**
- 镜像 `redis:7-alpine`，`imagePullPolicy: Never`，args `--appendonly yes`
- 容器端口 `6379`（无端口名），探针 `exec redis-cli ping`
- **没有设 resources**（线上就是 `resources: {}`）
- 数据盘 = `volumeClaimTemplates` 的 `data`（2Gi，RWO，Filesystem）
  ⇒ 线上对应 PVC `data-redis-0`（local-path，绑在 **zifang002**）
  ⇒ StatefulSet 用 volumeClaimTemplates，**不需要**独立 PVC 文件
- Service `redis`：NodePort `30901 → 6379`，selector `app=redis`

**StatefulSet `z-cache`**
- 镜像 `docker.io/library/z-cache:1.3.6`，`imagePullPolicy: Never`
- 容器端口 `6379`（端口名 `redis`），探针 `tcpSocket 6379`
- env：`ZCACHE_HOST=0.0.0.0` / `ZCACHE_PORT=6379` / `ZCACHE_MAX_ENTRIES=0`
- resources：requests `100m` / `256Mi`，limits `memory 1Gi`（**无 cpu limit**，照抄线上）
- `nodeSelector: kubernetes.io/hostname=zifang001`
- 数据盘 = **独立 PVC** `z-cache-data`（2Gi，local-path，绑在 **zifang001**）⇒ 见 `04-pvc.yaml`
- Service `z-cache`：NodePort `31001 → 6379`，selector `app=z-cache`

**两个 StatefulSet 都有** `persistentVolumeClaimRetentionPolicy: Retain/Retain`（删/缩容不删盘）、
`podManagementPolicy: OrderedReady`、`revisionHistoryLimit: 10`（⇒ `rollout undo` 可用）。

⚠ **本域没有 secret**。

## 1. 怎么构建镜像

### 现状：仓内**有** Dockerfile（z-cache/Dockerfile），但当前**构建不起来**

`z-cache/Dockerfile` 是多阶段构建（maven 编译 → jre 运行时），**构建定义在仓内**，
这点比其它 7 个域好。但它有一处硬伤：

- Dockerfile 里 `COPY ./z-cache/.build-context/settings.xml /tmp/settings.xml`
  ⇒ **该文件在本仓不存在**（`z-cache/.build-context/` 目录都没有）
  ⇒ build 会在这一步直接失败。
- 另外它的 `COPY ./z-cache ./z-cache` / `COPY ./z-cache/pom.xml` 说明
  **构建上下文是本仓的父目录**（`z-cache/` 的上一级），这与仓内 `docker-compose.yml` 的口径一致
  （`build: context: ..` + `dockerfile: z-cache/Dockerfile` + `additional_contexts: maven-m2`）。

线上镜像口径：裸引用 `docker.io/library/z-cache:1.3.6`（带官方 library 前缀，**不是** Nexus 前缀），
只存在于两个节点的 containerd，不在任何 registry。
redis 那份是官方 `redis:7-alpine`，同样只存在于节点本地。

### 建议命令（可执行；缺的文件需人工补进仓）

```bash
# 0) 先补 Dockerfile 缺的那个文件（内容可从 z-wf / z-ops 等仓的 maven settings 抄，
#    或用一个只配了中央仓库镜像的最小 settings.xml）——本任务不改仓内非 deploy/ 的文件。
#    目标路径：z-cache/.build-context/settings.xml

# 1) 构建（上下文 = 本仓的父目录，即工作区根）
cd /Users/zifang/workplace/ceo_workplace/z-opc-foundation
docker build \
  --build-context maven-m2="$HOME/.m2" \
  -f z-cache/Dockerfile \
  -t z-cache:1.3.6 \
  .

# 2) ⚠ 关键一步：补上官方 library 前缀，让本地 tag 与清单里的 image: 逐字一致
docker tag z-cache:1.3.6 docker.io/library/z-cache:1.3.6

# 3) 官方 redis 镜像也要在本地（它同样是 imagePullPolicy: Never，节点上必须有）
docker pull redis:7-alpine
```

## 2. 怎么喂 containerd

铁律三条：

1. **裸 tag，禁 Nexus 前缀**——本集群 `imagePullPolicy: Never`，k3s 不会去 registry 拉，
   它按 `image:` 字段的**字面引用**去节点本地 content store 找。喂进去的引用带 Nexus 前缀
   （如 `nexus.internal/zopc/...`）而清单里写的是裸引用（或反过来）⇒ **ErrImageNeverPull**。
   ⚠ 本域的 `docker.io/library/z-cache:1.3.6` 带的是**官方 library 前缀**，照抄线上，
     不是 Nexus 前缀；喂的时候也必须带这个前缀（见 §1 的 docker tag 那步）。
   ⚠ `redis:7-alpine` 虽是官方镜像，但本集群 Never 策略 ⇒ 也得喂到节点上。
2. **必须用 `cat` 管道喂 `k3s ctr images import -`**——`ctr images import` 从 **stdin** 读，
   直接把文件路径当参数会报 `unrecognized image format`。
3. **两个节点都要喂**——两个节点都是工作节点，Pod 可能落在任一节点；只喂一个会出现
   `ErrImageNeverPull` + Pod 反复重建。

```bash
# 1) 在开发机上导出镜像（build 可在开发机做，不必上节点）
docker save docker.io/library/z-cache:1.3.6 > /tmp/z-cache-1.3.6.tar
docker save redis:7-alpine > /tmp/redis-7-alpine.tar
for f in /tmp/z-cache-1.3.6.tar /tmp/redis-7-alpine.tar; do
  scp "$f" zifang@192.168.31.231:/tmp/     # zifang001
  scp "$f" zifang@192.168.31.136:/tmp/     # zifang002
done

# 2) 喂 zifang001（192.168.31.231）——该节点 sudo 需要口令，会交互提示
for f in /tmp/z-cache-1.3.6.tar /tmp/redis-7-alpine.tar; do
  ssh zifang@192.168.31.231 "cat $f | sudo k3s ctr images import -"
done

# 3) 喂 zifang002（192.168.31.136）——该节点 sudo -n 可免密
for f in /tmp/z-cache-1.3.6.tar /tmp/redis-7-alpine.tar; do
  ssh zifang@192.168.31.136 "cat $f | sudo -n k3s ctr images import -"
done

# 4) 核验（两个节点都要看到，且引用必须与清单逐字一致）
ssh zifang@192.168.31.231 'sudo k3s ctr images ls | grep -E "z-cache|redis"'
ssh zifang@192.168.31.136 'sudo -n k3s ctr images ls | grep -E "z-cache|redis"'
```

一步到位的写法（不落临时文件，效果等价）：

```bash
docker save docker.io/library/z-cache:1.3.6 | ssh zifang@192.168.31.231 'cat - | sudo k3s ctr images import -'
docker save docker.io/library/z-cache:1.3.6 | ssh zifang@192.168.31.136 'cat - | sudo -n k3s ctr images import -'
docker save redis:7-alpine | ssh zifang@192.168.31.231 'cat - | sudo k3s ctr images import -'
docker save redis:7-alpine | ssh zifang@192.168.31.136 'cat - | sudo -n k3s ctr images import -'
```

## 3. 怎么 apply

```bash
# ⚠ 会改动生产集群，需要人工确认后再执行；本仓只产出清单，不代跑。
kubectl apply -f deploy/k8s/
```

执行顺序与前置条件（apply 前置检查）：

1. **namespace 先在**：`00-namespace.yaml` 已包含在目录里，`kubectl apply -f deploy/k8s/`
   会按文件名顺序（00 → 01 → 02 → 03 → 04）依次 apply，namespace 自动先建。
2. **镜像先在**：见 §2。**四个引用**（`docker.io/library/z-cache:1.3.6`、`redis:7-alpine`）
   两个节点都要喂齐，否则 Pod 卡 ErrImageNeverPull。
3. **PVC 的顺序无所谓，但别删**：`04-pvc.yaml` 对已有 PVC 是幂等更新。
   ⚠ **绝不要 `kubectl delete pvc z-cache-data -n z-cache`**——local-path 上删 PVC
     会连带删掉 PV 里的数据（缓存数据丢了要重建）。
4. 本域**无 secret**，不需要额外前置对象。
5. **本域被别的域依赖**：`z-indexer` 的 `REDIS_HOST=redis.z-cache.svc.cluster.local`
   ⇒ 指的是本域的 redis Service ⇒ 本域停了 z-indexer 会出问题。

apply 后自检：

```bash
kubectl -n z-cache get sts,svc,pvc,pods
kubectl -n z-cache describe sts z-cache | tail -30
kubectl -n z-cache describe sts redis | tail -30
```

## 4. 怎么回滚

StatefulSet 的回滚命令是 `rollout undo statefulset/<name>`（不是 `deploy`）：

```bash
# 看历史版本
kubectl -n z-cache rollout history statefulset/redis
kubectl -n z-cache rollout history statefulset/z-cache

# 回滚到上一个版本
kubectl -n z-cache rollout undo statefulset/redis
kubectl -n z-cache rollout undo statefulset/z-cache

# 回滚到指定版本
kubectl -n z-cache rollout undo statefulset/z-cache --to-revision=<N>

# 看回滚是否完成
kubectl -n z-cache rollout status statefulset/z-cache
```

⚠ **PVC 数据不会自动回滚**（本域尤其重要，两个 StatefulSet 都带数据盘）：

- `rollout undo` 只回滚 Pod 模板（镜像 + env + 资源配置），
  **不碰** `z-cache-data` 和 `data-redis-0` 两块盘里的数据。
- ⇒ 旧版本镜像与盘上的新格式数据可能不兼容（例如新版本改了 AOF / 条目序列化格式），
  需要人工判断：清空重建 or 手工迁移。
- ⇒ redis 的 AOF 也在盘上（`--appendonly yes`），回滚不会把 AOF 回退到旧版本。
- StatefulSet 回滚是**逐 Pod 滚动**的（OrderedReady），不是瞬间切回 ⇒ 会有短暂双实例期。

⚠ **回滚的前提是节点上有旧镜像**：回滚只改 StatefulSet 里的 `image:` 指向的旧引用，
该引用必须仍在这两个节点的 containerd 里（`k3s ctr images ls` 能看到）。旧镜像被清掉就回滚不动。

⚠ **`z-cache` 这个 StatefulSet 只能落在 zifang001**（nodeSelector + 本地盘绑定），
回滚时若 zifang001 不可用，Pod 会一直 Pending。

## 5. 本清单与线上的差异（抄写时发现）

| 项 | 线上 | 本清单 | 原因 |
|---|---|---|---|
| livenessProbe | 无（两个 StatefulSet 都只有 readinessProbe） | 补了 livenessProbe（redis 用 exec ping、z-cache 用 tcpSocket） | 满足「每个容器至少一个探针」的可观测性下限；端点与线上 readiness 完全一致，语义不新增假设 |
| Service 类型 | 两个 Service 都是 NodePort（有 clusterIP），而 StatefulSet 的 governing service 按 k8s 规范应是 headless | 照抄（未改） | 改了会动到既有访问方式，需单独评估；已知偏差：拿不到 per-pod 稳定 DNS |
| labels | Service / StatefulSet / PVC 上带 `compare-target: redis` | 照抄保留 | 线上既有标签，不动 |
| redis 的 resources | 空（`resources: {}`） | 照抄为空 | 不擅自加资源限制 |
| z-cache 的 resources.limits | 只有 `memory: 1Gi`（无 cpu limit） | 照抄 | 不擅自加 cpu limit |
| SA 令牌自动挂载 | `automount != false`（未关） | 同样不含该字段 | 本域是两个 **StatefulSet**，而 SA 令牌收敛的方案 §十 把 **10 个 StatefulSet 列为卡内口径外的扩展项**（有状态滚动会按序号逐个重建 ⇒ 有数据面中断窗口，需单独安排、清单来源分散）⇒ **当前未关是已知且有意的状态，不是漏项**；本清单也不引入该字段 |

## 6. 待办（不在本任务范围内）

- **`z-cache/Dockerfile` 当前构建不起来**：`z-cache/.build-context/settings.xml` 不存在（§1）。
  需人工补该文件（或改造 Dockerfile 去掉 settings 依赖）并提交。
- 本仓的 `docker-compose.yml` 默认镜像名是 `z-cache:latest`，与线上 `docker.io/library/z-cache:1.3.6`
  不是同一条引用 ⇒ 照 compose 建出来的镜像**不能**直接喂进集群（引用不匹配 ⇒ ErrImageNeverPull）。
  需要固定 `ZCACHE_IMAGE=docker.io/library/z-cache:1.3.6` 或在流程里补 `docker tag`（§1 的第 2 步）。
- 两个 StatefulSet 的 governing service 都是 NodePort 而非 headless（§5），属规范偏差，改动需单独评估。
