# 部署指南

> 本文是[项目 README](../README.md) 的部署分册，从服务器选型一直讲到日常运维。

面向「个人平台」这种小体量项目：**一台 2核2G 的轻量云服务器，用 Docker Compose 把 MySQL + Redis + 后端 + 前端一起跑起来**。

---

## 一、服务器怎么选

> 用的是**阿里云 ECS**？直接看 [deploy-aliyun.md](deploy-aliyun.md)，那边有针对阿里云的价格坑（续费同价）、
> 安全组配法、专属镜像加速器，以及「影像还在腾讯云 COS」的跨云算账。
> 想一分钱不花？看 [deploy-free.md](deploy-free.md)。

### 关键前提：要不要备案

这是最先要决定的，直接影响选哪里的机器：

| | 中国大陆机房 | 香港 / 海外机房 |
| --- | --- | --- |
| 域名备案 | **必须**，1~20 个工作日，需实名 | 不需要 |
| 国内访问速度 | 快（延迟 20~50ms） | 一般（延迟 50~150ms，晚高峰易抖动） |
| 价格 | 有大量新用户特惠，很便宜 | 略贵 |

- **只有 IP、不用域名**：大陆机房也可以，但 80/443 端口未备案会被拦，得用非标准端口访问。
- **想用域名 + 图省事**：选香港/海外，免备案，代价是国内访问慢一些。
- 你是个人项目，如果平时只给自己和少数人看，**香港轻量 + 非标端口** 或 **大陆轻量 + 备案** 都可行。

### 配置建议

这个项目的实际开销：Spring Boot 堆内存约 300~500MB、MySQL 约 300~400MB、Nginx 内存占用极小。

| 配置 | 是否可行 | 说明 |
| --- | --- | --- |
| 2核2G | **推荐起步** | 够用。MySQL 需要按 `docker-compose.yml` 里那样限制 buffer pool |
| 2核1G | 勉强 | 必须加 swap，且 MySQL 要压到 128M buffer pool，容易 OOM |
| 2核4G | 舒服 | 以后加 Redis、Elasticsearch 也有余量 |

**磁盘**：40GB 起步。注意影像本身放在腾讯云 COS 上，不占服务器磁盘，所以 40GB 只装系统和数据库，够用很久。

### 价格参考（2025 年，新用户特惠）

> 以下数字来自公开的促销文章，**价格波动很大，务必以官网活动页实时价为准**。

**腾讯云轻量应用服务器**（[参考](https://cloud.tencent.cn/developer/article/2581833?policyId=1004)）：

| 配置 | 促销价 |
| --- | --- |
| 4核4G3M / 40G SSD / 300G 月流量 | 38 元/年（限时秒杀）/ 79 元/年 |
| 2核2G3M / 40G SSD / 200G 月流量 | 68 元/年 |
| 2核2G4M | 99 元/年（**续费同价**） |
| 2核4G6M / 70G SSD / 600G 月流量 | 528 元/3 年 |

**阿里云轻量应用服务器**（[参考](https://developer.aliyun.com/article/1690936)）：

| 配置 | 促销价 |
| --- | --- |
| 轻量 2核2G / 40G ESSD / 峰值 200M | 38 元/年（每天 10 点、15 点抢购），常规 68 元/年 |
| 经济型 e 2核2G / 40G / 3M | 99 元/年（**续费同价**） |
| 通用算力型 u1 2核4G / 80G / 5M | 199 元/年（**续费同价**） |

**京东云**：2核4G / 5M / 500G 月流量，约 149 元/年（新人）。

### 三个避坑点

1. **一定选「续费同价」的机型。** 38 元/年那种秒杀款，第二年续费常常涨到 1000+ 元/年。这个坑比首年贵几十块严重得多。
2. **流量比带宽重要。** 影像走 COS，服务器流量主要是网页和接口，200G/月够个人用。但如果你打算去掉 COS、把影像存服务器本地，流量会涨得很快，要重新算。
3. **优先选你 COS 所在的那家云、同一个地域。** 你的 COS 在腾讯云广州，那就用腾讯云广州的机器 —— 可以走内网访问，省流量费、延迟也更低。

### 完全免费的方案

**Oracle Cloud 永久免费层**（[官方说明](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm)）配置相当大方：

- ARM（Ampere A1）：**2 OCPU + 12GB 内存**，或拆分最多 2 台
- AMD 微型实例：最多 2 台，各 1GB 内存
- 块存储 200GB，出网流量 10TB/月，外加 50GB 的 MySQL HeatWave

对 2核2G 来说这是绰绰有余的。但有几个现实问题：

- 注册需要**国际信用卡**验证（国内双币卡有时能过）
- 机房在海外（东京/首尔/大阪等），国内访问延迟高
- **闲置回收**：官方明确写了，7 天内 CPU 95 分位 <20%、网络 <20%、内存 <20% 会被判定为闲置并回收。个人平台访问量低，有被回收的风险
- 热门区域经常 `out of host capacity`，抢不到实例

适合当练手环境，**不建议把主站放上去**。

其他 PaaS（Railway、Render、Fly.io、Vercel + PlanetScale）也能跑，免费额度通常只有 512MB 内存，Spring Boot 跑起来很勉强，MySQL 免费额度也有行数/容量限制。**个人项目还是自建一台 VPS 最省心。**

---

## 二、部署步骤

前置：服务器装好 Docker 与 Compose（腾讯云/阿里云选 Ubuntu 22.04 镜像，然后 `curl -fsSL https://get.docker.com | sh`）。

### 1. 把代码传到服务器

```bash
# 方式一：git（推荐，方便后续更新）
git clone <你的仓库地址> evidence && cd evidence

# 方式二：本地打包上传（注意排除 target/ 和 .idea/）
scp -r ./* root@<服务器IP>:/root/evidence/
```

> `application-local.yml` 被 `.gitignore` 排除了，**不会**进仓库 —— 这正是我们想要的，服务器上的密钥走环境变量注入。

### 2. 配置环境变量

```bash
cd evidence
cp deploy/.env.example deploy/.env
vi deploy/.env      # 填 MySQL 密码、Redis 密码、腾讯云 COS 五个参数
chmod 600 deploy/.env   # 里面是明文密钥，收紧权限
```

COS 的五个参数抄你本地 `src/main/resources/application-local.yml` 里的值即可。

### 3. 启动

```bash
docker compose --env-file deploy/.env -f deploy/docker-compose.yml up -d --build
```

第一次会构建镜像（Maven 要下依赖，慢一些）。起来后看状态：

```bash
docker compose --env-file deploy/.env -f deploy/docker-compose.yml ps
docker compose --env-file deploy/.env -f deploy/docker-compose.yml logs -f app
```

看到 `Started DemoApplication` 就成功了，浏览器访问 `http://<服务器IP>/`。

### 4. 建管理员账号

数据库里的用户密码是 **加盐 MD5**（盐值 `yupi`），不能直接 INSERT 明文。两种方式：

**方式一（推荐）**：先用前端注册一个普通账号，再用 SQL 改成管理员：

```bash
docker exec -it evidence-mysql mysql -uroot -p evidence
```

```sql
UPDATE user SET userRole = 'admin' WHERE userAccount = '你的账号';
```

**方式二**：直接插入算好的哈希。盐值 `yupi` + 明文密码做 MD5：

```sql
INSERT INTO user (id, userAccount, userPassword, userName, userRole)
VALUES (1, 'admin', '<yupi+密码 的 MD5>', '管理员', 'admin');
```

### 5. 加域名和 HTTPS（可选）

- 域名解析 A 记录指向服务器 IP
- 大陆机房要先在云厂商备案系统提交
- HTTPS 用 Nginx 挂证书，或直接在前面加一层云厂商的免费 SSL

---

## 三、Redis 与列表缓存

`docker-compose.yml` 里 Redis 已经配好并起了，后端也**已经接上多级缓存**：

```
Caffeine（本机，5 分钟）→ Redis（分布式，5~10 分钟随机）→ MySQL
```

接口：`POST /evidence/list/page/vo/cache`，只缓存普通用户可见的公开数据（强制 `reviewStatus = 已通过`），所以缓存内容与用户身份无关，可以共用同一个 key。

前端默认**走不带缓存的接口**，由 `front/config.js` 里的开关控制：

```js
window.__USE_CACHE__ = false;   // 本地没起 Redis 时保持 false
```

用提供的 Nginx 配置部署时，`sub_filter` 会把它自动改成 `true`（因为服务器上 Redis 是起着的）。**如果你自己不打算跑 Redis，记得把 `deploy/nginx/default.conf` 里那条 `__USE_CACHE__` 的 sub_filter 去掉**，否则首页会请求一个需要 Redis 的接口而加载失败。

本地想试缓存效果：

```bash
docker run -d --name redis -p 6379:6379 redis:7-alpine
# 然后把 front/config.js 里的 __USE_CACHE__ 改成 true
```

### 两个已知的缓存行为

1. **Redis 侧没有主动失效**。影像增删改、审核之后，代码会清掉本机 Caffeine（`clearEvidenceCache()`），但 Redis 里的数据靠 5~10 分钟 TTL 自然过期。单机部署下最多看到几分钟的旧列表，可以接受；**如果以后多实例部署、要求写入后立刻一致**，需要把 `EvidenceServiceImpl.clearEvidenceCache()` 里的注释落实 —— 一并删掉 Redis 中 `evidence:listVO:` 开头的 key。
2. **缓存 key 只覆盖公开列表**。因为强制了 `reviewStatus = 已通过`，管理端按状态筛选的请求不会被缓存，也不会互相污染。

### Redis 后续还能用在哪

- **登录态**：目前用 `HttpSession`（单机内存），以后扩多台必须换成 Redis 存 session
- **批量抓取限流**：防止有人反复触发抓取

---

## 四、日常运维

```bash
cd evidence

# 更新代码后重新部署
git pull
docker compose --env-file deploy/.env -f deploy/docker-compose.yml up -d --build app front

# 看日志
docker compose --env-file deploy/.env -f deploy/docker-compose.yml logs -f app

# 备份数据库（建议加到 crontab 每天跑）
docker exec evidence-mysql mysqldump -uroot -p$MYSQL_ROOT_PASSWORD evidence > backup-$(date +%F).sql

# 只重启后端
docker compose --env-file deploy/.env -f deploy/docker-compose.yml restart app
```

**注意**：`mysql-data` 卷里的数据是持久化的，但 `deploy/mysql/init.sql` **只在数据卷为空时执行一次**。以后加表/加字段，要手动进容器执行 SQL。

已经准备好的增量脚本：

```bash
# 给已有库补充缩略图字段（缩略图功能需要）
docker exec -i evidence-mysql mysql -uroot -p evidence < deploy/mysql/migration-add-thumbnail.sql
```

也可以直接进容器手动执行：

```bash
docker exec -it evidence-mysql mysql -uroot -p evidence
```

---

## 五、部署前必须知道的两个改动

我修了两个会直接导致部署失败的问题，你拉代码时会一起带上：

**1. `pom.xml` 里 `<skip>true</skip>` 导致打不出可执行 jar**

原配置会跳过 `repackage`，`mvn package` 产出的是 **0.1MB、没有 Main-Class、没有内嵌依赖** 的普通 jar，`java -jar` 会直接报错。已移除该配置，现在打出来是 **50MB 的可执行 fat jar**（已验证内嵌 87 个依赖）。

**2. `sql/` 下的建表脚本不能直接用于初始化**

- `create_table_sql.sql` 建的是 `yu_evidence` 库，而 `application.yml` 连的是 `evidence` 库，**对不上**
- `create_table_evidence.sql` 用 `ALTER TABLE` 追加审核字段，**重复执行会报 Duplicate column**
- 脚本里写死 `use evidence`，容器初始化时容易因库不存在而失败

已统一整理为 [`deploy/mysql/init.sql`](../deploy/mysql/init.sql)，全部 `CREATE TABLE IF NOT EXISTS`，审核字段直接建在表里，可重复执行。

**另外提醒**：`application.yml` 里的数据库密码是明文 `748374075`，服务器上通过 `SPRING_DATASOURCE_PASSWORD` 覆盖，别把本地密码带到线上。本地那份 yml 建议也改掉。

---

# 附：腾讯云专项落地清单

上面是通用流程，这一节是**腾讯云服务器**专用的：从控制台买机器到线上验收，按顺序照做即可。

## 6.1 买机器

| 项目 | 建议 | 说明 |
| --- | --- | --- |
| 机型 | **轻量应用服务器** | 便宜、自带防火墙面板，个人项目够用；预算宽松或用得上云硬盘/VPC 就选 CVM |
| 镜像 | Ubuntu 22.04 LTS | Docker 装起来最顺；不要选 Windows |
| 地域 | **跟 COS 同地域** | 你的桶在 `ap-guangzhou`，机器也选**广州/广州三区**，走内网访问，省流量费、延迟低 |
| 配置 | 2 核 2G / 40G SSD | 起步够用；2 核 4G 更宽松 |
| 带宽 | 3~4 Mbps | 影像走 COS，服务器只出网页和接口，够用 |
| 购买方式 | **挑「续费同价」的机型** | 38 元/年那种秒杀款，第二年常涨到上千元 |

## 6.2 安全组 / 防火墙（最容易漏的一步）

轻量服务器叫「防火墙」，CVM 叫「安全组」，**只放行下面这几个端口**：

| 方向 | 端口 | 来源 | 用途 |
| --- | --- | --- | --- |
| 入站 | TCP 22 | **只填你的家庭公网 IP/32** | SSH 登录 |
| 入站 | TCP 80 | 0.0.0.0/0 | HTTP |
| 入站 | TCP 443 | 0.0.0.0/0 | HTTPS（配了证书才需要） |
| 入站 | TCP 8080 | 0.0.0.0/0 | 未备案时用非标端口（可选） |
| 出站 | 全部 | 0.0.0.0/0 | 拉镜像、调 COS、调内容审核 |

> **绝对不要**把 3306（MySQL）、6379（Redis）、8666（后端）开放到公网。
> `deploy/docker-compose.yml` 里这三个服务都没有往宿主机映射端口，安全组再关一层，双保险。
> 公网数据库被扫到爆破是这类个人项目最常见的翻车方式。

## 6.3 备案与端口：先用哪条路

| 场景 | 能用的端口 | 说明 |
| --- | --- | --- |
| 大陆机器 + 域名，**已备案** | 80 / 443 | 最正规，体验最好 |
| 大陆机器 + 域名，**未备案** | 只能非标端口 | 80/443 会被运营商拦，表现为「域名打不开、`IP:8080` 能开」 |
| 大陆机器 + 只用 IP | 非标端口 | 在 `.env` 里设 `HTTP_PORT=8080`，访问 `http://<IP>:8080/` |
| 香港 / 新加坡机器 | 80 / 443 随便用 | 免备案，代价是国内访问慢一些（50~150ms） |

改端口只要动 `.env` 一行：

```bash
HTTP_PORT=8080
```

然后重新 `bash deploy/deploy.sh`，记得安全组里同时放行 8080。

## 6.4 装 Docker

```bash
sudo apt update && sudo apt install -y git curl
curl -fsSL https://get.docker.com | sudo sh
sudo systemctl enable --now docker
sudo usermod -aG docker $USER     # 重新登录后就不用每次 sudo
docker compose version            # 需要 v2，Docker 20.10+
```

拉镜像慢的话配一下加速器（`/etc/docker/daemon.json`），改完 `sudo systemctl restart docker`：

```json
{
  "registry-mirrors": ["https://mirror.ccs.tencentyun.com"],
  "log-driver": "json-file",
  "log-opts": { "max-size": "10m", "max-file": "3" }
}
```

> `mirror.ccs.tencentyun.com` 只在**腾讯云内网**可用；如果是别的云或本机，换成对应的公共加速地址。

**2 核 2G 的机器，构建前建议先加 2G swap**，否则 Maven 构建可能 OOM 被 kill：

```bash
sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile
sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

## 6.5 COS 子账号最小权限（重要，别用主账号密钥）

服务器上放的是**明文密钥**，一旦机器被入侵，主账号密钥等于整个云账号失守。正确做法：

1. 腾讯云控制台 → **访问管理 CAM** → 用户 → 新建用户 → **自定义创建** → 只勾「编程访问」（不要勾控制台登录）
2. 不给它任何预设策略，新建一条**自定义策略**（把 `1504102210` 换成你的 APPID、桶名换成你的）：

```json
{
  "version": "2.0",
  "statement": [
    {
      "effect": "allow",
      "action": [
        "cos:GetService", "cos:GetBucket", "cos:HeadBucket",
        "cos:PutObject", "cos:GetObject", "cos:HeadObject", "cos:DeleteObject",
        "ci:*"
      ],
      "resource": [
        "qcs::cos:ap-guangzhou:uid/1504102210:evidence-1504102210/*",
        "qcs::cos:ap-guangzhou:uid/1504102210:evidence-1504102210/"
      ]
    }
  ]
}
```

3. 把生成的 SecretId / SecretKey 填到 `.env` 的 `COS_SECRET_ID` / `COS_SECRET_KEY`

> `ci:*` 是数据万象（Data CI）的权限 —— 本项目的影像压缩、缩略图、主色调 `imageAve` 都走它。
> 只给一个桶的权限，密钥泄露的影响面就被限制在这一个桶里。
>
> **顺带提醒**：你本地 `application-local.yml` 里用的是主账号密钥，建议也在控制台轮换一次，
> 并把本地那份也换成子账号密钥。

## 6.6 部署：三种方式选一个

### 方式 A：服务器上拉代码 + 一键脚本（推荐）

```bash
# 1. 本机先把改动提交推送到远端（重要：没推送的代码服务器拉不到）
git add -A && git commit -m "..." && git push

# 2. 服务器上
sudo mkdir -p /root && cd /root
git clone <你的仓库地址> evidence && cd evidence
cp deploy/.env.example deploy/.env
vi deploy/.env            # 填 MySQL 密码、Redis 密码、5 个 COS 参数
chmod 600 deploy/.env
bash deploy/deploy.sh     # 构建 + 启动 + 健康检查，失败自动回滚
```

看到 `发布成功 ✅` 就好了。打开 `http://<服务器IP>:<HTTP_PORT>/`。

`deploy.sh` 做的事：校验 `.env`（拦住没改的占位密码）→ 校验 Nginx 配置 → 按 git 短 SHA 打镜像标签 → 构建启动 → 轮询接口做健康检查 → 不通过就自动回滚到上一版本并打印日志。

### 方式 B：本机构建镜像推到腾讯云 TCR（服务器不装 Maven）

适合服务器配置低、或想统一在本地/CI 构建的场景：

```bash
# 本机（先登录腾讯云容器镜像服务，控制台开通「个人版」即可）
docker login ccr.ccs.tencentyun.com
TAG=$(git rev-parse --short HEAD)
docker build -t ccr.ccs.tencentyun.com/<命名档案库>/evidence-app:$TAG .
docker push ccr.ccs.tencentyun.com/<命名档案库>/evidence-app:$TAG
```

服务器上的 `deploy/docker-compose.yml` 把 `app.build` 换成：

```yaml
  app:
    image: ccr.ccs.tencentyun.com/<命名档案库>/evidence-app:${EVIDENCE_TAG}
    # 删掉 build 段
```

再 `EVIDENCE_TAG=<你的tag> docker compose --env-file deploy/.env up -d`。

> 镜像标签用 git SHA、不用 `latest`，是回滚能落地的前提 —— `deploy.sh` 也是这么做的。

### 方式 C：宝塔 / 1Panel 面板

面板里装 Docker → 上传代码 → 用 Compose 模板粘贴 `deploy/docker-compose.yml`。可视化但多一层黑盒，**排障时不如命令行清楚**，出问题仍要会看 `docker compose logs`。

## 6.7 加域名 + HTTPS（腾讯云免费证书）

1. 域名解析：在 DNSPod 加一条 A 记录指向服务器公网 IP
2. 腾讯云控制台 → **SSL 证书** → 申请**免费证书**（仅单域名，**有效期 3 个月**，一年需续 4 次）→ 签下来后下载 **Nginx 格式**，得到 `xxx_bundle.crt` 和 `xxx.key`
3. 传到服务器并收紧权限：

```bash
sudo mkdir -p /etc/nginx/ssl && cd /etc/nginx/ssl
sudo vi evidence.example.com_bundle.crt   # 粘贴 crt 内容
sudo vi evidence.example.com.key          # 粘贴 key 内容
sudo chmod 600 *.key
```

4. 改 `deploy/nginx/https.conf` 里的 `server_name` 与证书文件名（两处）
5. 用 override 文件叠加启动：

```bash
cd /root/evidence
docker compose --env-file deploy/.env \
  -f deploy/docker-compose.yml \
  -f deploy/docker-compose.https.yml up -d
```

6. 安全组放行 443，访问 `https://evidence.example.com` 验证证书生效

> **免费证书已经不是 1 年期了**：腾讯云、阿里云都跟随 CA/B 论坛新规，自 2024 年起把免费 DV 证书
> 从 12 个月缩短到 **3 个月**，且不支持泛域名、不支持续期补齐时长，到期只能重新申请（每年额度有限，腾讯云 20~50 张、阿里云 20 张，以控制台为准）。
> 所以要么在日历上加个提前 15 天的提醒，要么直接改用 `acme.sh` / Certbot 自动续期，
> 或把域名托管到 Cloudflare 用它的免费证书（自动签发、自动续期、支持泛域名）。
> `https.conf` 里的 HSTS 头默认注释掉了，确认 HTTPS 长期稳定后再开。

## 6.8 发布验收清单（每次上线都走一遍）

```bash
# 1. 四个容器都在 Up
docker compose --env-file deploy/.env -f deploy/docker-compose.yml ps

# 2. 后端健康（未登录会返回 code 40100，能拿到 JSON 就说明接口通了）
curl -s http://127.0.0.1:${HTTP_PORT}/api/user/get/login

# 3. 前端配置替换真的生效了（这是最容易静默失败的一处）
curl -s http://127.0.0.1:${HTTP_PORT}/config.js | grep __API_BASE__
#   期望看到：window.__API_BASE__ = '/api';      ← 如果是空字符串，说明 sub_filter 没生效

# 4. 页面能开、影像能出、能登录、能上传、按颜色能查到图、档案库分析能出图
```

第 3 步是最值得记住的一条：**页面能打开 ≠ 配置正确**。`sub_filter` 没生效时页面照常显示，但所有接口请求都发到了访问者自己电脑的 `localhost`，只有打开浏览器 Network 面板才看得出来。

## 6.9 排障清单

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 首页影像全部空白，接口 500，日志报 `Unknown column 'picColor'` | 库是早期版本，缺主色调字段 | `docker exec -i evidence-mysql mysql -uroot -p evidence < deploy/mysql/migration-add-evidence-color.sql` |
| 页面能开，但接口请求发到了 `localhost:8666` | Nginx `sub_filter` 未生效（缺 `sub_filter_types`） | 已修复；确认容器里是新配置：`docker compose exec front nginx -T \| grep sub_filter_types` |
| 同样地，缺 `thumbnailUrl` 字段报错 | 早期版本没有缩略图字段 | `docker exec -i evidence-mysql mysql -uroot -p evidence < deploy/mysql/migration-add-thumbnail.sql` |
| 后端起不来，日志有 COS 相关异常 | `.env` 里 COS 参数没填全 | 5 个 `COS_*` 一个都不能少，`deploy.sh` 会提前拦住 |
| 上传报 413 | Nginx 或后端体积限制太小 | Nginx `client_max_body_size 20m`，后端 `max-file-size/max-request-size 10MB`，改要同时改 |
| 磁盘很快满 | 容器日志无限制 | 已加 `logging` 轮转（单文件 10m、留 3 个） |
| `IP:8080` 能开、域名打不开 | 未备案却被运营商拦 80/443 | 走备案，或继续用非标端口 |
| 改了表结构但不生效 | `init.sql` 只在数据卷为空时执行一次 | 用 `deploy/mysql/migration-*.sql` 手工执行 |
| 容器一直重启，`ps` 看不到 Up | 配置错误导致进程退出 | `docker compose logs --tail=100 <服务名>` |

## 6.10 日常运维与回滚

```bash
cd /root/evidence

# 发布 / 更新（会自动健康检查 + 失败回滚）
git pull && bash deploy/deploy.sh

# 看日志
docker compose --env-file deploy/.env -f deploy/docker-compose.yml logs -f app

# 手工回滚到某个版本（把一个旧 tag 顶回当前 tag 名即可）
docker images | grep evidence-app
docker tag evidence-app:<旧tag> evidence-app:$(git rev-parse --short HEAD)
docker compose --env-file deploy/.env -f deploy/docker-compose.yml up -d app

# 备份数据库（建议加 crontab）
echo '0 3 * * * cd /root/evidence && bash deploy/backup.sh >> /var/log/evidence-backup.log 2>&1' | sudo tee -a /etc/crontab
```

备份只覆盖数据库。**影像本体在 COS 上**，建议在 COS 控制台开启**版本控制**，防止误删无法恢复。

## 6.11 加固清单（按性价比排序）

1. **COS 用子账号最小权限密钥**（见 6.5）—— 收益最大，成本最低
2. **SSH 禁密码登录、改非 22 端口、只允许密钥**：
   ```bash
   # 本机生成密钥后 ssh-copy-id，再改 /etc/ssh/sshd_config：
   # PermitRootLogin prohibit-password
   # PasswordAuthentication no
   sudo systemctl restart ssh
   ```
3. **安全组只放行必要端口**，MySQL/Redis/后端端口不对公网开放
4. 开启**腾讯云免费的主机安全（云镜）**做基础入侵检测
5. 开启 **COS 版本控制**；数据库备份保留 7 天以上并考虑异地副本
6. 数据库不要用 root 跑应用：改好 `deploy/mysql/create-app-user.sql` 里的密码并执行它，然后在 `.env` 里设 `MYSQL_USERNAME=evidence_app` 与 `MYSQL_PASSWORD=<该密码>`，让应用只拿到 `evidence` 库的增删改查权限
7. 配置**云监控告警**：CPU 持续 >80%、内存 >85%、磁盘 >85%、带宽跑满 —— 这四个是最容易先炸的
8. 打开 `apt` 自动安全更新：`sudo apt install -y unattended-upgrades && sudo dpkg-reconfigure -plow unattended-upgrades`
