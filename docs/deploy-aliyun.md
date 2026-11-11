# 阿里云 ECS 部署指南

> 本项目部署部分是**云无关**的（Docker Compose 跑 MySQL + 后端 + Nginx 前端），换云只影响四件事：
> **安全组配法、镜像加速器、备案流程、影像存储选哪家**。
> 所以通用步骤照 [deploy.md](deploy.md) 走，本文只讲阿里云特有的部分和踩坑点。

---

## 〇、最短路径：从零到能访问，10 步

> 这是照着敲就能跑完的顺序版，细节与坑在后面的章节里展开。
> **影像继续用腾讯云 COS，不需要迁 OSS**（原因见第六节）。

### 第 0 步｜本机准备（两件事，别跳过）

```bash
# ① 代码要推到 GitHub，服务器才能拉得到（当前有未提交改动就先提交）
git add -A && git commit -m "准备部署" && git push

# ② 生成 deploy/.env（自动读 application-local.yml 里的 COS 参数 + 随机生成数据库密码）
bash deploy/make-env.sh
```

> 仓库地址是 `https://github.com/lsj-lsj54/pic.git`。
> **如果是私有仓库**，服务器上 `git clone` 不能用密码，要用 Personal Access Token：
> `git clone https://<你的token>@github.com/lsj-lsj54/pic.git evidence`
> 公开仓库直接 clone 即可。

### 第 1 步｜买机器

阿里云 ECS 或轻量应用服务器，**2 核 2G 起步**，镜像选 **Ubuntu 22.04**，地域选离你近的。
省钱要点：**ECS 经济型 e（99 元/年，续费同价）**，别为了省 30 元买 38 元的秒杀款（次年涨到 68）。

### 第 2 步｜安全组 / 防火墙放行端口

| 端口 | 来源 |
| --- | --- |
| 22 | 只填你的公网 IP |
| 80（未备案用 8080） | 0.0.0.0/0 |
| 443（配了 HTTPS 才需要） | 0.0.0.0/0 |

**不要开 3306 / 6379 / 8666。**

### 第 3 步｜登录服务器

```bash
ssh root@<公网IP>
```

### 第 4 步｜装 Docker

```bash
curl -fsSL https://get.docker.com | sudo sh
sudo systemctl enable --now docker
sudo usermod -aG docker $USER && newgrp docker
docker compose version          # 确认是 v2
```

2 核 2G 建议先加 2G swap（否则在服务器上跑 Maven 构建可能被 OOM Kill）：

```bash
sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile
sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

### 第 5 步｜拉代码

```bash
cd /root
git clone https://github.com/lsj-lsj54/pic.git evidence
cd evidence
```

### 第 6 步｜把 .env 传上去

```bash
# 本机执行（在项目目录里）
scp deploy/.env root@<公网IP>:/root/evidence/deploy/.env

# 服务器上执行
cd /root/evidence && chmod 600 deploy/.env
# 没备案就把端口改掉：vi deploy/.env  →  HTTP_PORT=8080
```

### 第 7 步｜一键发布

```bash
bash deploy/deploy.sh
```

脚本会自动判断：**内存 < 3G 走免 Redis 的低配编排，≥ 3G 走完整版（带 Redis）**。
想强制指定：`DEPLOY_MODE=free bash deploy/deploy.sh`。

它依次做：校验 `.env`（拦住没改的占位密码）→ 校验 Nginx 配置 → 按 git SHA 打镜像标签 → 构建启动 →
轮询接口做健康检查 → **不通过自动回滚到上一版本并打印日志**。

### 第 8 步｜验证

```bash
# 容器都在 Up
docker compose --env-file deploy/.env -f deploy/docker-compose.free.yml ps

# 后端通了（未登录会返回 code 40100，能拿到 JSON 就对了）
curl -s http://127.0.0.1/api/user/get/login

# 前端拿到的后端地址是对的（这一步最容易静默失败）
curl -s http://127.0.0.1/config.js | grep __API_BASE__   # 期望看到 '/api'
```

> 端口不是 80 的话把上面的 `127.0.0.1` 换成 `127.0.0.1:8080`。
> 低配模式用的是 `default.no-cache.conf`（不打开缓存开关），所以不用检查 `__USE_CACHE__`。
> 想确认容器实际加载的配置：`docker compose ... exec front nginx -T | grep sub_filter_types`

浏览器打开 `http://<公网IP>:<端口>/`，能看到影像列表就成功了。

### 第 9 步｜建管理员账号

先用前端注册一个普通账号，再改角色：

```bash
docker exec -it evidence-mysql mysql -uroot -p evidence
# 输入 .env 里的 MYSQL_ROOT_PASSWORD，然后：
UPDATE user SET userRole = 'admin' WHERE userAccount = '你的账号';
```

### 第 10 步｜域名 + HTTPS（可选）

1. 备案 → DNSPod 加 A 记录指向公网 IP
2. 申请证书（**免费证书只有 3 个月**，见第八节）
3. 用 HTTPS override 启动：
   ```bash
   docker compose --env-file deploy/.env -f deploy/docker-compose.yml -f deploy/docker-compose.https.yml up -d
   ```

### 以后更新代码

```bash
cd /root/evidence && git pull && bash deploy/deploy.sh
```

---

## 一、买哪台：重点不是首年价，是「续费同价」

2026 年阿里云的服务器优惠（**价格波动频繁，下单前务必以官网实时价为准**）：

| 机型 | 配置 | 首年 | 续费 | 关键规则 |
| --- | --- | --- | --- | --- |
| 轻量应用服务器（秒杀款） | 2核2G / 40G ESSD / 200M 峰值 | 38 元 | **68 元（不保价）** | 每天 10:00、15:00 限量抢，同一主体限购 1 台，**不支持续费同价** |
| **轻量应用服务器（常规款）** | 同上 | 68 元 | **续费同价** | 不用抢购，可叠加新用户满减券 |
| **ECS 经济型 e 实例** | 2核2G / 3M 固定带宽 / 40G ESSD Entry | **99 元** | **续费同价**（优惠期至 2029-03-31） | **新老用户同享**，每年可续 1 次 |
| ECS 通用算力型 u1 | 2核4G / 5M / 80G | 199 元 | 续费同价 | 企业实名用户 |
| ECS 通用算力型 u1 | 2核8G / 10M / 100G | 450 元 | 续费同价 | 个人新用户 |

**三个必须知道的点：**

1. **别为了省那 30 元去抢 38 元的秒杀款。** 它不支持续费同价，第二年就按 68 元走 —— 而你为了这 30 元还要每天蹲点抢。
2. **⚠️ ECS 一旦变配（升配/降配），就永久失去续费同价权益。** 买了 99 元的 e 实例就安安稳稳用它，别手痒动配置。
3. **阿里云轻量是「不限流量」**（200M 峰值带宽）—— 这点比按流量计的方案宽松，影像不走服务器的情况下完全够用。

学生的话：学信网认证后可以领 300 元无门槛券。

**配置够不够**：2 核 2G 跑本项目够用（**建议直接上 `docker-compose.free.yml` 免 Redis 编排**，省内存更稳）。要跑完整版（带 Redis）建议 2 核 4G。

---

## 二、安全组（阿里云叫「安全组」，轻量叫「防火墙」）

| 方向 | 端口 | 来源 | 用途 |
| --- | --- | --- | --- |
| 入方向 | TCP 22 | **只填你的公网 IP/32** | SSH |
| 入方向 | TCP 80 | 0.0.0.0/0 | HTTP |
| 入方向 | TCP 443 | 0.0.0.0/0 | HTTPS（配了证书才需要） |
| 入方向 | TCP 8080 | 0.0.0.0/0 | 未备案时用非标端口（可选） |

> **不要开 3306 / 6379 / 8666。** `docker-compose.yml` 里这三个服务都没往宿主机映射端口，安全组再关一层。
>
> 阿里云 ECS 在「安全组」里配，**轻量应用服务器在「防火墙」里配**，名字不同、入口不同，换机型时容易漏。

---

## 三、备案

- 大陆节点 + 域名 + 80/443 → **必须备案**
- 备案要在阿里云控制台申请**备案服务号**（可申请数量与实例规格/购买时长有关，以控制台提示为准）
- 备案期间先用 `HTTP_PORT=8080` 跑，用 `http://<IP>:8080` 访问
- 想免备案：选阿里云的**香港地域**节点

---

## 四、装 Docker（用阿里云专属镜像加速）

```bash
curl -fsSL https://get.docker.com | sudo sh
sudo systemctl enable --now docker
sudo usermod -aG docker $USER && newgrp docker
docker compose version
```

**镜像加速器要用你自己账号的专属地址**：控制台 → 容器镜像服务 ACR → 镜像工具 → 镜像加速器。
公共加速器（包括文档里流传的那些）经常失效，专属地址更稳：

```json
// /etc/docker/daemon.json
{
  "registry-mirrors": ["https://<你的专属ID>.mirror.aliyuncs.com"],
  "log-driver": "json-file",
  "log-opts": { "max-size": "10m", "max-file": "3" }
}
```

改完 `sudo systemctl restart docker`。

> 2 核 2G 的机器**先加 2G swap**，否则在 ECS 上跑 Maven 构建可能被 OOM Kill：
> ```bash
> sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile
> sudo mkswap /swapfile && sudo swapon /swapfile
> echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
> ```

---

## 五、部署

```bash
git clone <你的仓库地址> evidence && cd evidence
cp deploy/.env.example deploy/.env
vi deploy/.env          # 填 MySQL 密码 + 5 个 COS 参数
chmod 600 deploy/.env

# 推荐：2核2G 用免 Redis 的低配编排
docker compose --env-file deploy/.env -f deploy/docker-compose.free.yml up -d --build

# 或者用一键脚本（会自动健康检查 + 失败回滚，走完整版编排）
bash deploy/deploy.sh
```

发完打开 `http://<公网IP>:<HTTP_PORT>/`，验收清单见 [deploy.md 6.8 节](deploy.md#68-发布验收清单每次上线都走一遍)。

---

## 六、⚠️ 跨云的现实：影像还在腾讯云 COS

这是个必须先想清楚的问题：**服务器搬到阿里云，但对象存储还在腾讯云。**

先分清「谁出网、谁付费」：

| 环节 | 走谁的流量 | 说明 |
| --- | --- | --- |
| 用户看图 | **腾讯云 COS** | 浏览器直接从 COS 公网拉图，ECS 完全不参与 |
| 上传影像 | 浏览器 → ECS → COS | ECS 出网那一段：**固定带宽机型（如 e 实例 3M 不限流量）不额外按流量收费**，只占带宽；按流量计费的机型会产生少量费用 |
| 接口请求 | 阿里云 ECS | 文字/JSON 为主，量极小 |

**结论：对个人平台这个量级，跨云的成本主要是「要管两个控制台」，而不是钱。不用为了省流量费急着迁 OSS。**

值得做的三件事：

1. **ECS 地域选离用户近的**（已经和 COS 地域解耦了，不必再选广州）
2. **给 COS 挂 CDN**（腾讯云 CDN 或 Cloudflare）—— 影像永远是带宽大头
3. 等影像到几万张、或者月流量上了几十 GB，再考虑迁 OSS / R2

---

## 七、如果决定迁阿里云 OSS：工作量评估

我把代码里的 COS 耦合点都看了一遍，**好消息是「取主色调」这一处几乎不用改**：

| | 腾讯云 COS | 阿里云 OSS |
| --- | --- | --- |
| 接口 | `影像URL?imageAve` | `?x-oss-process=image/average-hue`，或老式的 `影像URL@imageAve` |
| 返回 | `{"RGB":"0x5c783b"}` | `@imageAve` 返回**完全一样的** `{"RGB":"0x5c783b"}`；`average-hue` 返回纯文本 `0x5c783b` |

也就是说 `CosManager.getMainColor()` 里解析 `RGB` 字段的逻辑可以原样复用，只改 URL 拼法。

要改的地方，按工作量从大到小排：

| 位置 | 改什么 | 工作量 |
| --- | --- | --- |
| `CosManager.putEvidenceObject` + `buildCompressRules` | **最大的一处**。现在用的是 COS 数据万象的「上传时持久化处理」（`PicOperations` + `imageMogr2/format/webp`，一次请求产出原图 + 缩略图）。OSS 上要么改用 IMM 做持久化，要么改成「只传原图，缩略图由 `x-oss-process` 实时生成」 | 中~大 |
| `pom.xml` | 依赖 `cos_api` → `aliyun-sdk-oss` | 小 |
| `CosManager` 其余方法 | `putObject` / `getObject` / `deleteObject` / `parseKeyFromUrl` → OSS SDK 的同名 API | 小 |
| `CosClientConfig` + 配置项 | `cos.client.*` → `oss.*`（endpoint / bucket / ak / sk），compose 与 `.env` 同步改 | 小 |
| `getMainColor` | 只改 URL 拼接 | 极小 |
| 存量影像 | 要把已上传的图从 COS 搬到 OSS（跨云有流量费，或用 coscli / ossimport） | 中 |

> **顺带的架构收益**：迁 OSS 时**可以干脆不做「持久化缩略图」**，改成 `?x-oss-process=image/resize,w_300` 实时出图 ——
> 省掉一半存储空间，也不用维护两份文件。代价是每次读图都要过一次影像处理（有计算费），
> 但配合 CDN 缓存后基本可以忽略。

需要的话我可以把这套 OSS 适配做掉（含单元测试和配置迁移），大概是一个完整的工作单元。

---

## 八、阿里云特有的排障清单

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 能 SSH，但网页打不开 | 安全组/防火墙没放行 80 | 轻量在「防火墙」页，ECS 在「安全组」里加规则 |
| 域名打不开、`IP:8080` 能开 | 未备案，80/443 被运营商拦 | 走备案，或继续用非标端口 |
| `docker pull` 很慢或失败 | 没配 ACR 专属加速器 | 控制台拿专属地址写进 `daemon.json` |
| 证书三个月后网站报错 | 免费证书有效期就是 3 个月 | 加日历提醒，或改用 `acme.sh` 自动续期 |
| 想升配却发现续费变贵了 | **变配会永久失去续费同价权益** | 动配置前先算清长期成本 |
| 账单出现额外流量费 | 按流量计费机型 + ECS 往 COS 传图 / 公网下载 | 换固定带宽机型，或把影像迁到 CDN |
| 2核2G 构建时被 Killed | Maven 构建吃内存 | 加 swap，或改用 `deploy/docker-compose.free.yml` |

---

## 九、可选加固

1. **云监控告警**：CPU 持续 >80%、内存 >85%、磁盘 >85%、带宽跑满 —— 这四个最先出问题
2. **自动快照策略**：系统盘 + 数据盘都配上（按容量计费，很便宜），出事能回滚
3. **用 ACR 存镜像**：在本地/CI 构建好推上去，ECS 只 pull，省掉每次构建的 2~5 分钟和内存峰值
4. **安全组 + SSH 双收紧**：详见 [deploy.md 6.11 加固清单](deploy.md)
5. 数据库/Redis 一律不要暴露公网（本项目的编排已经默认不映射端口）
