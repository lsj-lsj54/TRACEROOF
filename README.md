# 证链 TraceProof · 物流异常件影像取证平台

![Java](https://img.shields.io/badge/Java-8%20%7C%20JDK%2017-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-2.6.13-6db33f)
![MyBatis-Plus](https://img.shields.io/badge/MyBatis--Plus-3.5.9-2f6fed)
![Sa-Token](https://img.shields.io/badge/Sa--Token-1.39.0-2f6fed)
![MySQL](https://img.shields.io/badge/MySQL-8.0-4479a1)
![Redis](https://img.shields.io/badge/Redis-7-dc382d)
![COS](https://img.shields.io/badge/对象存储-腾讯云%20COS-0052d9)
![Redisson](https://img.shields.io/badge/Redisson-3.17.7-c11d31)
![WebSocket](https://img.shields.io/badge/WebSocket-协同编辑-6db33f)
![Frontend](https://img.shields.io/badge/前端-零依赖%20ES%20Module-success)

面向快递 / 物流网点的异常件（破损、丢失、污染、延误、错分）处理场景：网点人员现场拍照上传取证影像，平台自动压缩生成缩略图，客服审核定责后进入公共案例库；各网点按「**公共案例库 / 私有档案库 / 团队档案库**」三套模型归档管理，支持多人协同核对与多维度异常统计。

后端 Spring Boot + MyBatis-Plus + Sa-Token + MySQL + Redis + 腾讯云 COS；前端零依赖原生 ES Module（无构建、无第三方运行时依赖）；
配套 Docker Compose 一套命令部署上线。

| | 说明 |
| --- | --- |
| **取证上传** | 本地拍照文件 / 影像链接 / 典型案例批量采集三条入口，单张超过 900KB 在前端先压缩，后端统一走模板方法落对象存储，影像即证据、全程留痕 |
| **加速** | 一次上传产出「原图 + webp 缩略图」，列表页用缩略图、定责核对用原图；列表查询走 Caffeine → Redis → MySQL 三级读取 |
| **审核定责** | 非管理员上传的取证影像自动进入待审核，客服（管理员）审核定责通过后才进入公共案例库，支持驳回与改判 |
| **档案库** | 公共案例库 + 网点私有档案库 + 网点团队档案库三级模型，档案库按级别（普通/专业/旗舰）限数量与容量，用量随影像增删实时结算；团队档案库支持网点成员协作（viewer / editor / admin 三级角色） |
| **权限** | Sa-Token 统一登录态与权限：全局角色 + 档案库维度动态权限码，配合注解校验，影像、档案库、成员操作都有归属与角色双重校验，保证「哪个网点的证据只能哪个网点改」 |

> 详细部署（服务器选型、Docker Compose、Nginx、HTTPS、日常运维）见 [docs/deploy.md](docs/deploy.md)，免费方案见 [docs/deploy-free.md](docs/deploy-free.md)。

<!-- 想放截图时：把影像放到 docs/images/ 下，然后取消下面这段注释即可（在 GitHub 上会渲染成表格）

## 界面预览

| 影像浏览 | 我的档案库 | 档案库详情 |
| --- | --- | --- |
| ![](docs/images/gallery.png) | ![](docs/images/archive.png) | ![](docs/images/archive-detail.png) |

| 影像管理（管理员） | 上传（本地压缩） | 影像详情 |
| --- | --- | --- |
| ![](docs/images/admin-evidence.png) | ![](docs/images/upload.png) | ![](docs/images/detail.png) |

-->

---

## 目录

- [功能一览](#功能一览)
- [技术栈](#技术栈)
- [系统架构](#系统架构)
- [目录结构](#目录结构)
- [核心设计与实现](#核心设计与实现)
- [工程实践与踩坑记录](#工程实践与踩坑记录)
- [快速开始](#快速开始)
- [接口一览](#接口一览)
- [测试](#测试)
- [部署](#部署)
- [后续规划](#后续规划)

---

## 功能一览

| 模块 | 能力 |
| --- | --- |
| 用户 | 注册（MD5 加盐）、登录 / 注销、登录态自动恢复（Sa-Token 会话，存 Redis）、角色（user / admin）、注解式权限校验、按昵称/账号模糊搜索 |
| 团队档案库 | 每人可创建 1 个私有 + 1 个团队档案库；团队档案库按昵称邀请成员、调整角色（viewer / editor / admin）、移除成员或自行退出；成员可查看、按角色可操作；「我加入的团队档案库」独立区块 |
| 影像浏览 | 分页网格、名称/简介关键词搜索（防抖）、分类筛选、按时间/体积排序、详情弹窗（缩略图 → 原图）、查看原图、按主色调（腾讯云 imageAve）查图（工具栏取色器 + 容差滑块，卡片主色块可一键「按此色找相似」） |
| 影像上传 | 本地文件（可多选、前端 canvas 压缩）、影像链接导入、典型案例批量采集（必应，单次上限 30 张、自动去重，用于培训案例库） |
| 影像管理 | 分页查询、按审核状态筛选、通过 / 拒绝（可改判）、编辑信息（标签数组 ↔ JSON）、重新上传、删除（库 + 缓存 + 对象存储三处一致） |
| 我的影像 | 自己上传的公共案例库影像，带审核状态角标，可编辑 / 重传 / 删除 |
| 档案库 | 创建（类型：私有/团队 + 级别决定限额）、重命名、删除（连带清理档案库内影像与成员记录）、档案库列表带数量与容量进度条 |
| 档案库分析 | 档案库资源用量（容量/数量仪表盘）、异常类型分布（分组条形图）、标签词云、大小分段（饼图）、异常件上传趋势（按天/周/月折线）；管理员可汇总全系统并查看档案库使用排行（ECharts） |
| 交互与观感 | 全屏影像查看器（滚轮/双击缩放、拖拽平移、键盘 ←→/±/f 与缩略图导航）、卡片悬停遮罩 + 快速操作、列表骨架屏、Toast 堆叠与进度条、滚动渐入、按钮涟漪；统一设计 token（色彩/阴影/圆角/缓动）与 :focus-visible 焦点环，动效遵循 prefers-reduced-motion |
| 档案库内影像 | 上传到指定档案库（上传前校验数量、拿到真实体积后校验容量）、浏览、编辑、重传、删除，用量实时回填 |
| 在线协同编辑 | 多人同时核对同一张异常件影像（WebSocket + Disruptor），同一时刻仅一人持有编辑权，缩放/旋转实时广播给同图其他在线用户，编辑历史同步给中途加入者 |
| 高并发治理 | 档案库创建去重用 Redisson 分布式锁；单图查询前布隆过滤器预判防缓存穿透；上传/登录接口令牌桶限流 |
| 用户管理 | 用户分页、按昵称/角色筛选、新增、编辑、删除（不能删自己） |
| 前端体验 | hash 路由 + 视图懒加载、统一请求封装（自动解包 BaseResponse、40100 自动跳登录）、Toast / Modal / 分页等通用组件 |

---

## 技术栈

| 分类 | 选型 |
| --- | --- |
| 语言 / 构建 | Java 8 语言级别（构建与运行使用 JDK 17）、Maven 3.8 |
| 后端框架 | Spring Boot 2.6.13、Spring AOP、Sa-Token 1.39.0（登录态 + 角色/权限校验，会话存 Redis） |
| 持久层 | MyBatis-Plus 3.5.9（含 `mybatis-plus-jsqlparser` 分页插件）、MySQL 8、逻辑删除、雪花 ID |
| 分库分表 | ShardingSphere-JDBC 5.2.0（`evidence` 按 `archiveId` 分表，团队档案库一档案库一表） |
| 缓存 | Caffeine 3.1.8（本地一级）+ Redis / Spring Data Redis（分布式二级 + Sa-Token 会话） |
| 分布式能力 | Redisson 3.17.7（分布式锁 RLock / 布隆过滤器 RBloomFilter / 限流器 RRateLimiter） |
| 实时通信 | Spring WebSocket + LMAX Disruptor 3.4.4（在线协同编辑，消息异步消费） |
| 对象存储 | 腾讯云 COS Java SDK 5.6.227 + 数据万象（上传即产出 webp 压缩图） |
| 工具库 | Hutool 5.8.26（HTTP / 文件 / 日期）、jsoup 1.15.3（抓取解析）、Lombok |
| 接口文档 | Knife4j 4.4.0（OpenAPI 2 / Swagger） |
| 前端 | 原生 HTML + CSS + ES Module，零构建、零第三方运行时依赖；Node 内置模块写的静态服务器 + `/api` 反向代理 |
| 测试 | JUnit 5.10.2 + Mockito（后端）、自研 Node 冒烟与端到端脚本（前端） |
| 部署 | Docker 多阶段构建、Docker Compose（MySQL + Redis + 后端 + Nginx 前端）、Nginx 反向代理 |

---

## 系统架构

```mermaid
flowchart TB
    A[浏览器] --> B["Nginx / node server.js<br/>静态资源 + /api 反向代理"]
    B -->|/api/**| C["Spring Boot<br/>Controller → Service → Mapper<br/>AOP 鉴权 / 全局异常 / 参数校验"]
    C --> D[("MySQL 8<br/>业务数据")]
    C --> E[("Redis<br/>缓存 · 会话 · Redisson")]
    C --> F[("腾讯云 COS<br/>原图 + 缩略图")]
    E -.-> G["Caffeine 本地缓存<br/>（进程内一级）"]
```

> Redis 承载四类职责：列表缓存、Sa-Token 会话、Redisson（分布式锁 / 布隆过滤器 / 限流器）。

四条主要链路：

1. **上传**：`Controller` 鉴权 → `Service` 组装入参（校验 / 档案库归属 / 配额）→ `EvidenceUploadTemplate` 模板方法（校验 → 生成 key → 落临时文件 → 上传 COS → 封装结果 → 清理临时文件）→ 写库 → 维护档案库用量 → 清缓存。
2. **列表**：`/evidence/list/page/vo`（普通查询）或 `/evidence/list/page/vo/cache`（带缓存）→ Caffeine 命中即返回；未命中查 Redis；仍未命中查 MySQL 并回填两级缓存。
3. **访问影像**：浏览器直接读 COS 地址（**前提：存储桶配置为公共读**，或改用 CDN / 临时签名 URL），后端只负责存储与元数据，不代理影像流量。
4. **协同编辑**：浏览器建立 WebSocket（握手鉴权）→ 消息进 Disruptor RingBuffer 异步消费 → 编辑锁校验 → 广播给同图其他在线用户。

---

## 目录结构

```
.
├── src/main/java/com/traceproof
│   ├── controller          # 接口层：user / evidence / archive / archiveUser / file
│   │   └── aop             # 鉴权/限流切面（@AuthCheck 全局角色、@SaArchiveCheckPermission 档案库角色、@RateLimit 限流）
│   ├── service             # 业务层接口与实现（含档案库配额、缓存、审核逻辑）
│   ├── websocket           # 在线协同编辑（Handler / Disruptor 生产者·消费者 / 握手拦截器）
│   ├── conifg              # 配置类（Sa-Token、WebSocket、Disruptor、Redisson、Jackson）
│   ├── annotation          # 自定义注解（AuthCheck、SaArchiveCheckPermission、RateLimit）
│   ├── manager             # 外部能力封装
│   │   ├── CosManager      # 对象存储：上传、压缩规则、按 url 反查 key 删除、设置 ACL
│   │   ├── FileManager     # 早期上传实现（已废弃，保留对比）
│   │   ├── EvidenceBloomFilter # 影像 id 布隆过滤器（防缓存穿透，启动回填）
│   │   ├── factory         # 上传方式工厂（file / url）
│   │   ├── sharding        # 动态分表管理（团队档案库一档案库一表）
│   │   └── uplode          # 上传模板与两种实现（本地文件 / URL 下载）
│   ├── mapper              # MyBatis-Plus Mapper
│   ├── model
│   │   ├── entity          # 数据库实体
│   │   ├── dto             # 请求对象（按模块分包）
│   │   ├── vo              # 返回对象（统一提供 voToEntity / entityToVo）
│   │   └── enums           # 档案库级别、档案库类型、审核状态、上传方式、用户/档案库角色
│   ├── common              # 统一响应、分页基类、权限工具、全局异常处理
│   └── exception           # 错误码与业务异常
├── src/main/resources
│   ├── application.yml         # 主配置（端口 / 数据源 / multipart / Sa-Token）
│   └── application-local.yml   # 本地密钥（已 gitignore，需自行创建）
├── src/test/java           # 单元测试 167 项
├── front/                  # 前端（原生 ES Module，详见 front/README.md）
│   ├── js/{api,ui,app}.js  # 请求封装 / 通用组件 / 路由入口
│   ├── js/views/           # 各页面（浏览、我的影像、我的档案库、档案库详情、管理端）
│   ├── js/components/      # 上传、详情、编辑弹窗、协同编辑（coEdit.js / evidenceEditSocket.js）与影像压缩
│   ├── server.js           # 零依赖静态服务器 + /api 代理
│   ├── smoke-test.mjs      # 模块冒烟检查；e2e-test.mjs 端到端回归；concurrency-test.mjs 并发压测
├── sql/                    # 建表脚本（user / evidence / archive / archive_user）
├── deploy/                 # Dockerfile 之外的部署资产：compose、mysql 初始化、nginx、.env 模板
├── docs/deploy.md          # 部署指南
├── Dockerfile              # 后端多阶段构建（Maven 构建 + JRE 运行）
└── Dockerfile.front        # 前端 Nginx 镜像
```

---

## 核心设计与实现

### 1. 影像上传：模板方法 + 两种实现

本地文件与「影像链接导入」的流程只差三步（怎么校验、文件名从哪来、怎么落地成临时文件），其余完全一致。
因此把流程固化成模板方法 `EvidenceUploadTemplate.uploadEvidence()`（声明为 `final`，子类不可改流程），
只留三个抽象钩子给子类实现：

```
validEvidence(输入)      → 校验：类型白名单、体积上限（URL 额外做 HEAD 探测）
getOriginFilename(输入) → 取原始文件名（URL 会忽略 query、处理百分号编码）
processFile(输入, 文件) → 落地临时文件（本地转存 / 远程下载，边下边校验体积）
```

骨架统一负责：生成对象存储 key → 建临时文件 → 调子类落地 → 上传 → 解析结果 → **finally 清理临时文件**。
新增上传方式（比如以后接对象存储直传、多源导入）只需实现这三个方法并注册成 bean，工厂自动发现，不用改主干代码。

### 2. 缩略图：一次上传产出两份，按需压缩

上传时给 COS 数据万象带一条 `imageMogr2/format/webp` 规则，**一次请求同时产出原图与 webp 压缩图**，
压缩图与原图同目录、只换后缀（`/public/1/xxx.png` → `/public/1/xxx.webp`）。原图本身就是 webp 时跳过压缩，省一次存储与流量。
前端列表页加载缩略图、详情页加载原图，实测同一张图从 32.5 KB 降到 14.2 KB（省约 56%）。

### 3. 列表性能：Caffeine → Redis → MySQL 三级读取

- 只缓存**与用户身份无关**的公开数据：查询条件被强制为 `reviewStatus = 已通过` 且 `archiveId = 0`，因此缓存可以共用同一个 key，不会串号；
- 缓存 key 由真正影响结果的字段拼装后取 MD5，标签列表先排序再入 key（顺序不同不应产生两份缓存）；
- Redis TTL = 5 分钟 + 0~5 分钟随机抖动，避免同一时刻批量 key 失效造成缓存雪崩；Caffeine 本地缓存 5 分钟，比 Redis 稍短，两级自然错开；
- 影像增删改、审核后清理本地缓存；Redis 侧靠 TTL 自然过期（单机部署可接受，多实例部署的处理方式已在代码注释中写明）。

### 4. 影像审核：状态机 + 双端闭环

- 非管理员上传 / 编辑一律打回**待审核**；管理员操作自动过审，审核人与审核时间由后端写入，**前端无法伪造审核参数**；
- 审核状态变更后立即清缓存，公共案例库的可见性随之变化；
- 管理端支持改判（通过 ↔ 拒绝），拒绝时可带上审核信息。

### 5. 档案库模型：公共案例库 / 私有档案库 / 团队档案库 + 配额

- `archiveId = 0` 表示**公共案例库**（所有人可见）；`> 0` 表示**私有/团队档案库**，按档案库类型与成员角色控制可见性与操作权限；
- 档案库类型分两类：**私有档案库**（仅创建者与管理员可见可操作）与**团队档案库**（成员可查看，admin / editor 角色成员可操作），每用户各限 1 个；
- 档案库级别（普通版 100 张 / 100MB，专业版 1000 张 / 1GB，旗舰版 10000 张 / 10GB）在建档案库时自动填充限额，非管理员只能创建普通版；
- 上传先校验数量（放不下就不用白跑一趟上传），拿到真实体积后再校验容量，**超限会把刚传上去的文件从对象存储删掉**，不留垃圾；
- 用量（`totalCount` / `totalSize`）用 SQL 原子自增自减维护（`totalCount = totalCount + (?)`），避免「先查再更新」在并发上传时丢更新；
- 公共列表查询会**显式追加 `archiveId = 0`**：不加这个条件，私有档案库里的影像会出现在公共案例库中（越权泄漏）。

### 6. 分表：团队档案库「一个档案库一张表」

- 只对 `evidence` 表分片，`user` / `archive` / `archive_user` 保持单表；**只有团队档案库分表**，私有档案库与公共案例库共用原表 `evidence`；
- 分片键 `archiveId`（`0` = 公共案例库，`> 0` = 私有/团队档案库）。`EvidenceShardingAlgorithm` 对 `> 0` 返回 `evidence_<archiveId>`，对 `0`/`null` 返回原表 `evidence`；返回前必须校验表在不在 `actual-data-nodes` 里，不在就**主动回退原表**：5.2.0 的 `StandardShardingStrategy` 对非法表名直接抛 `ShardingRouteAlgorithmException`，且 **message 为空**，很容易误判成「表不存在」；
- 路由节点由 `DynamicShardingManager` 动态维护：团队档案库创建时 `CREATE TABLE IF NOT EXISTS evidence_<archiveId> LIKE evidence`，再把所有未删除团队档案库的表名拼回 `actual-data-nodes`（`alterRuleConfiguration` + `reloadDatabase`）；
- 建表必须在事务**提交之后**做：MySQL 的 DDL 会隐式提交当前事务，塞进 `transactionTemplate` 会让「档案库入库」变成不可回滚的半步操作；
- 启动时先补建历史团队档案库缺失的分表，再刷新节点 —— **顺序不能反**，节点里出现不存在的表会让该档案库所有查询报表不存在；
- 不带 `archiveId` 的查询（按 `id` 查详情、管理员审核列表）会被广播到全部分表再 `UNION ALL` 合并，功能正确但扫描成本翻倍，属于分片的固有代价；
- 档案库逻辑删除后分表**不自动 drop**（避免误删数据），重启后该表会从路由节点中剔除，数据留在库里。

### 7. 权限模型：Sa-Token 统一登录态与档案库角色

- **登录态**：`StpKit.ARCHIVE.login(userId)` 签发 token 写入 Cookie（`satoken`，**已开启 HttpOnly**——JS 读不到，靠浏览器自动携带，XSS 抄不走），前端沿用 `credentials: 'include'` 无需改动；会话存 Redis（`sa-token-redis-jackson`），重启/多实例不丢登录态；
- **两套 StpLogic**：默认的 `StpUtil` 与 `StpKit.ARCHIVE`（`new StpLogic("archive")`）是两个独立账号体系，项目只用后者；注解靠 `type = "archive"` 指定走这一套，`StpInterface` 里也按 `loginType` 分流；
- **动态权限**：实现 `StpInterface`，从请求体反解 `archiveId / evidenceId / archiveUserId`（依赖 `HttpRequestWrapperFilter` 让 body 可重复读），查 `archive_user` 得到角色，再按 `all/archiveUserAuthConfig.json` 里 `roles[].permissions` 翻译成权限码（`evidence:view/upload/edit/delete`、`archiveUser:manage`）；
- **校验入口**：`@SaArchiveCheckPermission` 为主（对官方 `@SaCheckPermission` 的 `@AliasFor` 包装，**必须重写注解解析策略**否则 `value` 丢失、校验退化为放行），service 层再用 `EvidencePermissionUtils` / `ArchivePermissionUtils.checkArchiveAuth` / `canManageMember` 兜一次；全局 admin 仍由 `@AuthCheck` 切面校验；
- **会话清理**：删除用户、以及管理员变更用户角色时调用 `StpKit.ARCHIVE.logout(userId)` 踢掉该用户所有端口，避免 token 在 Redis 里空转到 30 天超时；只改昵称之类普通字段不踢，避免误伤；
- **未登录 / 无权限**：`GlobalExceptionHandler` 统一映射 `NotLoginException → 40100`、`NotPermissionException/NotRoleException → 40101`，与原有错误码语义一致，前端无感。

### 8. 批量抓取

解析必应影像搜索的异步结果片段，优先取结果条目 `m` 属性里的 `murl`（原图直链），
依次回退到 `murl` / `data-src` / `absUrl(src)`，并处理协议相对地址；同一批内按地址去重、单张失败不影响其余、数量有上限。

### 9. 前端：零依赖的模块化 SPA

- hash 路由 + `import()` 视图懒加载，模块按 `api / ui / components / views` 分层，改完刷新即生效，没有构建步骤；
- 统一请求封装：自动解包 `BaseResponse`、携带 `credentials: 'include'` 维持登录态、`40100` 自动清理登录态并跳登录页；
- 上传前用 canvas 压缩（超过 900KB 或 1600px 才压，优先 webp 以保留透明，浏览器不支持时退回 jpeg 并铺白底），压缩后仍超 2MB 本地直接拦下；
- 所有渲染到页面的用户输入统一走 `escapeHtml`，避免存储型 XSS。

### 10. 在线协同编辑：WebSocket + Disruptor

- 连接地址 `ws://host:8666/api/ws/evidence/edit?evidenceId=xxx`，握手阶段 `WsHandshakeInterceptor` 校验登录态（`satoken` Cookie）+ 影像编辑权限（本人 / 管理员 / 团队档案库 editor·admin 成员），不通过直接拒绝连接；
- **编辑锁**：`ConcurrentHashMap.putIfAbsent(evidenceId, userId)` 原子抢占，同一时刻一张图只有一个编辑者，抢锁失败的人收到 `ERROR` 提示；编辑者退出（`EXIT_EDIT`）或断开连接自动释放锁；
- **异步解耦**：消息先丢进 Disruptor RingBuffer（`EvidenceEditEventProducer` → `EvidenceEditEventWorkHandler`），把处理从 WebSocket 收发线程里剥离，避免高频编辑动作阻塞连接；
- **广播与历史**：缩放 / 旋转动作广播给同图其他在线用户（排除发送者，避免重复应用）；编辑历史记录在 `evidenceEditRecodes`，中途加入的用户补发到最新状态；
- 前端 `coEdit.js` / `evidenceEditSocket.js` 用原生 WebSocket，零依赖，`ws://` 地址由 `__API_BASE__` 推导。

### 11. 高并发治理：分布式锁 + 布隆过滤器 + 接口限流

三项能力统一由 **Redisson** 支撑（与 Spring Data Redis 的 Lettuce 并存，各管各的连接）：

- **分布式锁**：档案库创建去重由本地 `synchronized` 升级为 `RLock.tryLock(3, 30, SECONDS)`，key 按用户隔离（`archive:add:<userId>`）。本地锁只能锁单机，分布式锁保证「一账号每种类型一个档案库」在集群部署下依然成立；
- **布隆过滤器防缓存穿透**：`EvidenceBloomFilter` 基于 `RBloomFilter`——启动时经 ShardingSphere 数据源广播查询回填全量影像 id，上传时增量写入，`/evidence/get`、`/evidence/get/vo` 查询前先 `mightContain(id)` 预判，判定「一定不存在」直接短路不查库；**回填失败降级为直接查库**（`ready` 标志），绝不错杀合法查询；
- **接口限流**：`@RateLimit` 注解 + `RateLimitAspect` 切面，基于 `RRateLimiter` 令牌桶（`RateType.OVERALL` 全局限流），挂在上传（10 次/分钟）与登录（30 次/分钟）接口；**Redis 不可用时限流降级放行**（fail-open），不让限流器变成单点故障。

---

## 工程实践与踩坑记录

这些都是真实踩到并修掉的问题，记录在此，也方便后来者少走弯路：

| 问题 | 现象 | 处理 |
| --- | --- | --- |
| 雪花 ID 精度丢失 | 19 位主键超过 JS `Number.MAX_SAFE_INTEGER`，前端拿到的 id 被改写，详情接口报「请求数据不存在」、分页 `total` 变 0 | 自定义 Jackson 序列化器：只把超出安全范围的 `Long` 转成字符串，`total`、`size` 等仍保持数字 |
| 分页 `total` 恒为 0 | MyBatis-Plus 3.5.9 起分页插件被拆到独立模块 | 显式引入 `mybatis-plus-jsqlparser` |
| 链式 wrapper 报错 | `lambdaQuery()` 的结果直接传给 `list()` / `update()` 时抛 `can not use this method for "getSqlFirst"`（3.5.9 的 `AbstractChainWrapper#getSqlFirst` 是抛异常实现） | 统一改用具体的 `LambdaQueryWrapper` / `LambdaUpdateWrapper` 传给 mapper |
| 循环依赖启动失败 | `EvidenceService` ↔ `ArchiveService` 互相注入，Boot 2.6+ 默认禁止循环引用 | 用 `@Lazy` 在一侧延迟注入，并在注释里说明原因 |
| 压缩图跑到桶根目录 | CI 规则的 `fileId` 用 `FileUtil.mainName(key)` 会丢掉目录部分 | 改成完整路径推导，保证压缩图与原图同目录 |
| 大文件上传必失败 | 只配了 `max-file-size` 没配 `max-request-size`，multipart 解析仍被请求体上限拦下，且报的是笼统的系统错误 | 两个上限都显式配置，并在 e2e 里加了 1 MiB / 1.5 MiB / 3 MiB 边界回归 |
| 删除对象存储无效 | 删除接口需要的是对象 key，直接把完整 url 传进去不会报错，但什么也没删掉 | 增加 `parseKeyFromUrl` 反推 key，并加「同 url 被多条记录引用则跳过」的保护 |
| CDN 地址导入被误杀 | 必应缩略图 `/th/id/OIP-C.TTm1he_xxx?w=155` 点号后面是 CDN 加密串，被当作文件后缀判为「文件类型错误」，合法影像传不上去 | 不再凭地址后缀判类型：先看 `Content-Type`，下载后按文件头（JPEG/PNG/WebP magic number）确认并纠正后缀；下载遇 5xx 自动重试一次 |
| Lombok 与 JDK 21 不兼容 | JDK 21 下编译报 `NoSuchFieldError: JCTree$JCImport.qualid` | 构建统一使用 JDK 17（Docker 构建镜像同样固定 17） |
| 影像 403 裂图 | 接口正常但 COS 影像 URL 返回 `AccessDenied`：桶是私有读、上传未设对象 ACL；或触发桶的防盗链 Referer 白名单拦截 | 上传后对原图与缩略图设置对象公有读 ACL，并提供批量修复接口；防盗链白名单需在控制台放行前端域名/localhost |
| 后端端口被环境变量覆盖 | 会话注入 `SERVER__PORT`（Spring Boot relaxed binding）优先级高于 `application.yml` 的 `server.port`，后端实际监听了别的端口 | 启动时显式 `SERVER_PORT=8666 -Dserver.port=8666` 覆盖 |
| 分片路由异常但没信息 | 团队档案库查询抛 `ShardingRouteAlgorithmException`，**message 是空的**，看上去像「表不存在」 | 真因是算法返回的表名不在 `actual-data-nodes` 展开集合里（`StandardShardingStrategy` 会做 `contains` 校验）。算法内改为「不在集合就回退原表」 |
| 动态分表数据源名写错 | 启动报 `Missing the data source name: 'yu_evidence'` | 拼 `actual-data-nodes` 时前缀要用 `spring.shardingsphere.datasource.names` 里的**数据源名**，不是数据库名（本项目是 `evidence`） |
| 分表节点混进不存在的表 | 查询某个团队档案库报 `Table doesn't exist` | 生成表名的条件必须和建表条件一致；启动时先补建缺失分表、再刷新路由节点，顺序反了必崩 |
| 公共案例库查询路由到团队分表 | `archiveId=0` 查公共案例库时，SQL 被路由到 `actual-data-nodes` 的第一张分表，返回 0 条 | SQL 里出现了**两条重复的 `archiveId=0`**（`getQueryWrapper` 拼一次、查询方法又 `eq("archiveId",0)` 一次），ShardingSphere 提取不到唯一分片键。解法：公共案例库只让 `getQueryWrapper` 拼一次 `archiveId=0`，查询方法里归一化 `setArchiveId(0L)` 后不要再手动追加 |

---

## 快速开始

### 环境要求

| 依赖 | 版本 / 说明 |
| --- | --- |
| JDK | **17**（Lombok 1.18.22 与 JDK 21 不兼容；源码语言级别为 Java 8） |
| Maven | 3.8+ |
| MySQL | 8.0 |
| Redis | 7（**必需**：Sa-Token 会话存 Redis，登录依赖 Redis 可用） |
| 腾讯云 COS | 需要一个存储桶，并在控制台开启数据万象影像处理 |
| Node.js | 18+（只用于启动前端静态服务器，前端本身无构建） |

### 1. 初始化数据库

```bash
mysql -uroot -p < deploy/mysql/init.sql
```

`deploy/mysql/init.sql` 全部是 `CREATE TABLE IF NOT EXISTS`，可重复执行，建的是 `evidence` 库，包含四张表：
`user`（用户）、`evidence`（影像，审核字段与 `archiveId` 直接建在表里）、`archive`（档案库，含 `archiveType` 类型字段）、`archive_user`（档案库成员关联）。

> 若从旧库升级，`sql/create_table_archiveuser.sql` 单独建档案库成员表，其它字段变更见 `deploy/mysql/migration-*.sql`。
>
> **分表不用手工建**：团队档案库的分表 `evidence_<archiveId>` 由程序在「创建档案库时」和「应用启动时」自动 `CREATE TABLE IF NOT EXISTS ... LIKE evidence`，初始化脚本里只需要原表 `evidence`。

### 2. 配置

| 配置 | 位置 | 说明 |
| --- | --- | --- |
| 端口 / context-path / multipart 上限 | `application.yml` | 后端 8666，接口前缀 `/api`，单请求与单文件上限均为 10MB |
| 数据库账号密码 | `application-local.yml` 或环境变量 | `application.yml` 里是 `${MYSQL_USERNAME:root}` / `${MYSQL_PASSWORD:}` 占位符，**仓库中不含任何密码** |
| 腾讯云 COS 五项参数 | `application-local.yml` 或环境变量 | `cos.client.host / secretId / secretKey / region / bucket` |
| Redis 连接 | `application-local.yml` | **必需**：Sa-Token 会话存 Redis，未启动时登录会报错 |
| Cookie 的 Secure 标记 | 环境变量 `SA_TOKEN_COOKIE_SECURE` | 默认 `false`（本地 HTTP 必须如此，否则浏览器不回传 Cookie）；生产走 HTTPS 时设成 `true`。`HttpOnly` 已固定开启 |

`src/main/resources/application-local.yml` **已被 `.gitignore` 排除**，本地按下面的结构自建即可（`spring.profiles.active=local` 会自动加载）：

```yaml
spring:
  datasource:
    username: root
    password: <你的本地数据库密码>
  redis:
    database: 3
    host: 127.0.0.1
    port: 6379
cos:
  client:
    host: https://your-bucket-appid.cos.ap-guangzhou.myqcloud.com
    secretId: <你的 SecretId>
    secretKey: <你的 SecretKey>
    region: ap-guangzhou
    bucket: your-bucket-appid
```

部署时不需要这个文件：`deploy/docker-compose.yml` 通过 `SPRING_DATASOURCE_*`、`COS_CLIENT_*` 等环境变量注入（模板见 `deploy/.env.example`）。

### 3. 启动后端

```bash
mvn clean package -DskipTests
java -jar target/demo-0.0.1-SNAPSHOT.jar
# 或开发模式：mvn spring-boot:run
```

启动后接口地址为 `http://localhost:8666/api`，接口文档 Knife4j：`http://localhost:8666/api/doc.html`。

### 4. 启动前端

```bash
cd front
node server.js        # 打开 http://localhost:8667
```

`front/server.js` 是零依赖静态服务器，并把 `/api/**` 原样代理到后端，浏览器眼里只有一个源，不存在跨域与 Cookie 问题。
用其他静态服务器（Live Server 等）打开 `front/` 目录也可以，此时走跨域，需要前后端都用 `localhost` 访问。

### 5. 创建管理员

密码是加盐 MD5（盐值见 `UserServiceImpl`），不能直接写明文。最省事的做法是先在前端注册一个账号，再改角色：

```sql
UPDATE user SET userRole = 'admin' WHERE userAccount = '你的账号';
```

---

## 接口一览

统一响应格式：`{ "code": 0, "data": ..., "message": "ok" }`；`code` 与 `ErrorCode` 一一对应（0 成功、40100 未登录、40101 无权限、40300 禁止访问、40400 不存在）。

### 用户

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| POST | `/user/register` | 注册（`userAccount`、`userPassword`、`checkPassword`，昵称 `userName` 可选，不传自动生成） | 公开 |
| POST | `/user/login` | 登录（Sa-Token 签发 token 写入 Cookie） | 公开 |
| GET | `/user/get/login` | 获取当前登录用户 | 登录 |
| POST | `/user/logout` | 注销 | 登录 |
| POST | `/user/update/my` | 修改自己的昵称（id 取自登录态，只能改自己） | 登录 |
| GET | `/user/search` | 按昵称/账号模糊搜索用户（脱敏，用于邀请成员） | 登录 |
| GET | `/user/get/vo` | 按 id 获取脱敏用户 | 公开 |
| POST | `/user/list/page/vo` | 用户分页 | 管理员 |
| POST | `/user/add`、`/user/update`、`/user/delete`、`GET /user/get` | 用户增删改查 | 管理员 |

### 影像

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| POST | `/evidence/upload` | 上传本地文件（可带 `id` 覆盖、`archiveId` 上传到档案库，`archiveId=0` 为公共案例库） | 登录 |
| POST | `/evidence/upload/url` | 影像链接导入 | 登录 |
| POST | `/evidence/upload/batch` | 关键词批量抓取 | 管理员 |
| POST | `/evidence/list/page/vo` | 分页查询（`archiveId=0` 查公共案例库，`>0` 查自己的档案库） | 公开 / 档案库创建者 |
| POST | `/evidence/list/page/vo/cache` | 分页查询（多级缓存，仅公共案例库） | 公开 |
| GET | `/evidence/get/vo` | 影像详情（私有档案库的影像仅创建者可见） | 公开 / 档案库创建者 |
| POST | `/evidence/edit` | 编辑自己的影像 | 本人或管理员 |
| POST | `/evidence/delete` | 删除影像（库 + 缓存 + 对象存储） | 本人或管理员 |
| POST | `/evidence/review` | 审核（通过 / 拒绝） | 管理员 |
| POST | `/evidence/update`、`GET /evidence/get`、`POST /evidence/list/page` | 管理端增改查 | 管理员 |

### 档案库

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| POST | `/archive/add` | 创建档案库（普通用户仅普通版） | 登录 |
| POST | `/archive/my/list/page/vo` | 我的档案库分页（用户 id 由后端从登录态写入） | 登录 |
| GET | `/archive/get/vo` | 档案库详情 | 档案库创建者或管理员 |
| POST | `/archive/edit` | 重命名档案库 | 档案库创建者或管理员 |
| POST | `/archive/delete` | 删除档案库（连带删除档案库内影像） | 档案库创建者或管理员 |
| GET | `/archive/list/level` | 档案库级别字典 | 公开 |
| POST | `/archive/list/page`、`/archive/list/page/vo`、`GET /archive/get`、`POST /archive/update` | 管理端 | 管理员 |

### 团队档案库成员

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| POST | `/archive/user/add` | 邀请用户加入团队档案库（`archiveId` / `userId` / `archiveRole`，角色缺省 viewer） | 档案库创建者 / 团队管理员 / 系统管理员 |
| POST | `/archive/user/delete` | 移除成员（成员也可用于退出团队） | 创建者 / 团队管理员 / 管理员，或成员本人 |
| POST | `/archive/user/edit` | 调整成员角色 | 档案库创建者 / 系统管理员 |
| POST | `/archive/user/list` | 查询档案库成员列表（可按角色筛选） | 档案库成员 / 创建者 / 管理员 |
| POST | `/archive/user/list/my` | 查询「我加入的团队档案库」列表 | 登录 |

### 文件（测试用）

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| POST | `/file/test/upload` | 上传任意文件到对象存储 | 管理员 |
| GET | `/file/test/download/` | 从对象存储下载 | 管理员 |

### 在线协同编辑（WebSocket）

| 类型 | 地址 | 说明 |
| --- | --- | --- |
| WS | `/ws/evidence/edit?evidenceId=xxx` | 建立连接（握手校验登录 + 影像编辑权限）。客户端发 `ENTER_EDIT` / `EXIT_EDIT` / `EDIT_ACTION`，服务端回 `INFO` / `ERROR` / `ENTER_EDIT` / `EXIT_EDIT` / `EDIT_ACTION` |

---

## 测试

### 后端单元测试（167 项）

```bash
mvn test
```

覆盖查询条件构造（排序、筛选、空值）、VO 与实体互转、档案库权限规则（私有/团队）、影像档案库鉴权、
上传模板（本地文件 / URL / 后缀嗅探 / 下载重试 / 体积与类型校验）、对象存储删除、缓存序列化与 key 稳定性，
以及一个 `@SpringBootTest` 上下文加载用例（校验 Bean 装配与循环依赖处理）。

### 前端冒烟与端到端（无需浏览器）

```bash
cd front
node smoke-test.mjs                  # 13 个模块真实 import + 首屏与档案库页面渲染
node e2e-test.mjs                    # 49 项真实后端回归
API_BASE=http://localhost:8688/api node e2e-test.mjs   # 打其他后端地址
```

`smoke-test.mjs` 搭了一个极简 DOM 桩真实加载所有模块，能查出语法、import 路径和首屏渲染阶段的运行时错误；
`e2e-test.mjs` 覆盖登录与会话、雪花 ID 精度、分页 `total` 自洽、详情接口、上传体积边界，
以及档案库的完整链路（创建 → 列表 → 详情 → 上传到档案库并校验用量累加 → 隔离校验 → 删除影像校验用量回落 → 重命名 → 删除）。

### 并发压测（HTTP + WebSocket 编辑锁）

```bash
cd front
node concurrency-test.mjs --users 5 --http 200                 # 一次性并发：5 人抢编辑权 + 200 并发请求
node concurrency-test.mjs --http 200 --requests 100000         # keep-alive 持续压测：200 连接打 10 万请求
```

`concurrency-test.mjs` 零依赖（Node 内置 fetch / WebSocket），覆盖两块：HTTP 接口并发压测（统计成功率与延迟分布），
以及协同编辑「编辑锁」并发——注册 N 个测试用户同时抢同一张图的编辑权，断言「恰 1 人成为编辑者、其余被拒」，并验证退出后锁能释放。
每次运行自动把结果落盘到 `front/test-logs/`。

---

## 部署

推荐用 Docker Compose 在一台 2核2G 的轻量服务器上跑起 MySQL + Redis + 后端 + Nginx 前端：

```bash
cp deploy/.env.example deploy/.env    # 填数据库密码与 COS 密钥（.env 已被 gitignore）
chmod 600 deploy/.env
bash deploy/deploy.sh                 # 校验 → 构建 → 启动 → 健康检查，失败自动回滚
```

也可以手动执行 compose（等价，只是没有健康门禁与回滚）：

```bash
docker compose --env-file deploy/.env -f deploy/docker-compose.yml up -d --build
```

配套脚本与资产：

| 文件 | 用途 |
| --- | --- |
| `deploy/make-env.sh` | **本机**生成 `deploy/.env`：自动读 `application-local.yml` 里的 COS 参数 + 随机生成数据库密码 |
| `deploy/deploy.sh` | 一键发布：拦住占位密码、预校验 Nginx 配置、按 git SHA 打标签、健康检查、失败自动回滚；按内存自动选完整版/低配版编排 |
| `deploy/backup.sh` | 数据库备份：mysqldump + gzip + 按天保留 + 可选上传 COS |
| `deploy/docker-compose.yml` | MySQL + Redis + 后端 + 前端；端口、镜像标签均可用 `.env` 覆盖 |
| `deploy/docker-compose.free.yml` | 免 Redis 的低配编排（1~2G 内存机器用），MySQL 压到 96M |
| `deploy/docker-compose.https.yml` | HTTPS 叠加配置（挂证书 + 443），配腾讯云免费证书用 |
| `deploy/mysql/init.sql` | 首次部署建库建表（`CREATE TABLE IF NOT EXISTS`，可重复执行） |
| `deploy/mysql/migration-*.sql` | 已有库的增量迁移（缩略图字段、主色调字段） |
| `deploy/mysql/create-app-user.sql` | 建只对 `evidence` 库有权限的应用账号（最小权限加固） |
| `deploy/nginx/default.conf` | HTTP 站点：静态托管 + `/api` 反代 + 运行时配置替换 |
| `deploy/nginx/https.conf` | HTTPS 站点：证书、TLS 参数、HSTS、80→443 跳转 |

服务器选型、备案、**腾讯云专项落地清单（安全组、COS 子账号最小权限、HTTPS、排障清单）**、日常运维与数据备份，见 **[docs/deploy.md](docs/deploy.md)**。

用**阿里云 ECS**？见 **[docs/deploy-aliyun.md](docs/deploy-aliyun.md)** —— 阿里云的价格与「续费同价」坑、安全组/防火墙、专属镜像加速器、备案，
以及**跨云场景下「影像还在腾讯云 COS」该怎么算账、要不要迁 OSS** 的完整评估。

**不想花钱？** 见 **[docs/deploy-free.md](docs/deploy-free.md)** —— 2026 年还活着的免费方案盘点（Oracle Always Free、Cloudflare Pages、Render…以及已经取消免费层的 Heroku / Fly.io / Koyeb / Railway）。

免 Redis 的低配编排（`deploy/docker-compose.free.yml`）适合 1~2G 内存的免费小机器：

```bash
docker compose --env-file deploy/.env -f deploy/docker-compose.free.yml up -d --build
```

---

## 后续规划

- **AI 辅助定责**：接入多模态模型识别异常类型与严重程度，给出定责建议；OCR 识别面单号自动关联运单
- **异常分析增强**：影像内容自动打标与聚类，输出异常类型分布、网点异常率排行、处理时效等可视化报表
- **团队档案库增强**：档案库级别的配额分配、成员操作审计日志、@提及与消息通知
- **消息队列异步化**：审核通知、缩略图异步生成、档案库用量异步结算，引入 MQ 削峰解耦
- **全文检索**：影像名称 / 标签 / 简介接入 Elasticsearch，替代 MySQL LIKE 模糊查询

---

## 说明

- 前端详细说明（页面、接口约定、开发辅助脚本）见 [front/README.md](front/README.md)。
- 项目早期参考了公开课程项目的工程设计，其后按物流异常件取证业务做了整体重构与领域模型迁移：业务上新增档案库与配额体系、审核定责闭环、多级缓存；
  工程上补齐了单元测试与端到端回归、修正了原实现中的若干缺陷（见[工程实践与踩坑记录](#工程实践与踩坑记录)），并完善了 Docker 部署方案。
- 仓库中不包含任何密钥：数据库账号密码与 COS 密钥都从 `application-local.yml`（已 gitignore）或环境变量读取，
  仓库里只有 `${...}` 占位符与 `deploy/.env.example` 模板。
- 欢迎提 Issue / PR；如果这个项目对你有帮助，点个 Star 就是最大的鼓励。
