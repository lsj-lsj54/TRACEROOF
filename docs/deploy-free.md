# 免费部署指南

> 本文是[部署指南](deploy.md)的姊妹篇，专门回答「一分钱不花能不能跑起来」。
>
> 结论先说：**能，但只有一条路是「永久免费且跑得动全栈」的** —— Oracle Cloud Always Free。
> 其余要么是额度/试用，要么内存小到跑不动 Spring Boot，要么会休眠。

---

## 一、一张表看清 2026 年的免费选项

| 方案 | 内存 | 永久? | 会休眠? | 能跑这套全栈? | 主要代价 |
| --- | --- | --- | --- | --- | --- |
| **Oracle Cloud Always Free**（ARM） | **2 核 12G** | ✅ 永久 | ❌ 不休眠 | ✅ 很舒服 | 注册难、热门区抢不到、有闲置回收规则 |
| Google Cloud 永久免费 e2-micro | 1 核 1G（美国区） | ✅ 永久 | ❌ | ⚠️ 勉强（要加 swap） | 只在美国指定区域、延迟高 |
| Render Free Web Service | 0.1 核 512M | ✅ 永久 | ✅ 15 分钟无请求就睡 | ⚠️ 很勉强 | 冷启动 30 秒以上、压力下 OOM |
| Cloudflare Pages（只放前端） | — | ✅ 永久 | ❌ | ✅ 前端完美 | 后端还得另找地方 |
| 腾讯云/阿里云 新用户试用 | 2 核 2G | ❌ 1~3 个月 | ❌ | ✅ | 到期后要付费 |
| ~~Heroku~~ | — | ❌ | — | — | 2022 年就取消免费层了 |
| ~~Fly.io~~ | — | ❌ | — | — | 2024-10-07 起新用户不再有免费额度 |
| ~~Koyeb~~ | — | ❌ | — | — | 2026-02 起新用户必须订阅 Pro（$29/月） |
| ~~Railway~~ | — | ❌ | — | — | 只剩一次性 $5 试用金，用完就没 |

> 免费层变化非常快（Oracle 在 2026 年 6 月悄悄把免费 ARM 从 4 核 24G 砍到 2 核 12G，没有公告）。
> 上表是 2026-10 核对过的状态，动手前请再到官网确认一次。

---

## 二、先搞清楚：这套项目能省掉什么

### ✅ Redis 可以完全不装（已实测）

本项目对 Redis 是**可选依赖**：只有 `/evidence/list/page/vo/cache` 这个带缓存的接口用它，其余接口（含首页列表）都不受影响。

实测：把后端的 Redis 指向一个不存在的端口，应用照常启动、列表接口正常返回数据。所以省掉 Redis 只是少了「一层缓存」，功能一点不缺。

这就是 `deploy/docker-compose.free.yml` 存在的意义（它同时也把 MySQL 内存压到了 96M）。

### ✅ 前端可以完全白嫖

`front/` 是**零构建的静态站**（原生 HTML + ES Module，没有 npm/webpack），所以能直接丢到免费静态托管上：

- **Cloudflare Pages**：不限流量、500 次构建/月、免费 HTTPS、可绑自己的域名、允许商业使用
- GitHub Pages：没有公布带宽上限，但只支持静态站

### ❌ 省不掉的只有两样

1. **JVM 内存**：Spring Boot 空跑就要 250~400MB，这是硬门槛（512MB 的免费容器很勉强）
2. **影像存储**：影像在对象存储上，这是唯一有长期成本的地方（见第五节）

---

## 三、方案 A：Oracle Cloud 永久免费（推荐）

「2 核 ARM + 12G 内存 + 200G 硬盘 + 每月 10TB 出网流量」，永久免费，不是试用。
同样配置在任何别的地方都要 25~50 美元/月。

### 3.1 注册（这一步最容易卡）

- 需要一张能过 **3D Secure** 验证的信用卡（国内双币卡有时能过，纯银联一般不行）
- 姓名、地址要和银行卡一致；**不要挂 VPN**；不要用隐私邮箱域名（会被风控拒）
- 被拒了别连续重试，换个浏览器/网络等一段时间再试
- **home region 一旦选定永久不可改**，Always Free 资源只能建在这里 —— 尽量选离你近的：首尔、东京、大阪、新加坡

### 3.2 开机器

- 机型：`VM.Standard.A1.Flex`（ARM Ampere）
- 配置：**2 OCPU + 12 GB 内存**（这是整个账号的免费额度总额，也可以拆成两台 1 核 6G）
- 镜像：**Ubuntu 22.04**（ARM 版），本项目全程用 Docker，选 Ubuntu 最顺
- 硬盘：默认 47G 起（免费额度总共 200G）

> **ARM 兼容性**：本项目用的都是官方镜像，都提供 `linux/arm64` 变体，理论上开箱即用。
> 但我没法在你的机器上替你验证，第一次拉镜像前花 10 秒确认一下：
>
> ```bash
> docker buildx imagetools inspect mysql:8.0 | head -20                # 找 linux/arm64
> docker buildx imagetools inspect eclipse-temurin:17-jre-jammy | head -20
> ```
>
> 万一是单架构镜像，把 `deploy/docker-compose.free.yml` 里的 `mysql:8.0` 换成 `mysql:8.0-oraclelinux8`
> 或 `mariadb:10.11`（两者都明确支持 arm64）即可。

### 3.3 ⚠️ 两层防火墙，缺一层都访问不了

这是 Oracle 上最容易卡住新手的地方，网上「能 ping 通但打不开网页」基本都是这里：

**第一层：云端安全列表（VCN → Security Lists / NSG）** —— 放行 80 端口（和 443）

**第二层：实例内部的 iptables** —— Oracle 的 Ubuntu 镜像默认只放行 22，**必须手动放行 80**：

```bash
sudo apt update && sudo apt install -y iptables-persistent
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 80 -j ACCEPT
sudo netfilter-persistent save
sudo systemctl restart netfilter-persistent
# 确认规则在
sudo iptables -L INPUT -n --line-numbers | head
```

### 3.4 部署

```bash
# 装 Docker
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker $USER && newgrp docker

# 拉代码、配变量
git clone <你的仓库地址> evidence && cd evidence
cp deploy/.env.example deploy/.env
vi deploy/.env          # 填 MySQL 密码 + 5 个 COS 参数
chmod 600 deploy/.env

# 12G 内存的机器：直接跑完整版（带 Redis，首页更快）
docker compose --env-file deploy/.env -f deploy/docker-compose.yml up -d --build

# 想省内存 / 不想要 Redis：用免费版编排（MySQL 压到 96M + 无 Redis）
docker compose --env-file deploy/.env -f deploy/docker-compose.free.yml up -d --build
```

> 12G 内存跑完整版毫无压力，**建议直接用完整版**（多一层 Redis 缓存，首页更快）。
> 免费版编排是给 1~2G 小机器准备的。

### 3.5 ⚠️ 必须知道的「闲置回收」规则

Oracle 官方规则：**7 天窗口内，如果 CPU 95 分位 < 20% 且 网络 < 20% 且 内存 < 20%（仅 ARM），实例可能被判定闲置并回收。**

问题是：一个访问量低的个人平台，三个指标**很可能都低于 20%**（12G 内存只用了 1G 左右 = 8%）。也就是说，**放着不管有被收走的风险**。

两个通行解法：

1. **把账号升级成按量付费（PAYG）** —— Always Free 的资源依然是 $0，但闲置回收规则不再适用。记得设预算告警。这是社区里最常规的做法。
2. 让机器真正有点负载（跑个监控面板、定时任务），但效果不如方案 1 可靠。

### 3.6 其他坑

- **抢不到容量**：热门区经常报 `Out of host capacity`。换可用域、换区域，或写个重试脚本慢慢刷
- **30 天不登录**账号可能被暂停；**25 端口默认封禁**（本项目不涉及）
- 免费层的技术支持基本靠自己，出问题只能查文档

---

## 四、方案 B：前端白嫖 + 后端放小机器（最灵活）

适合「想把前端也放到全球 CDN 上」或者「后端拿不到 12G 免费机器」的情况。

```
浏览器 ──▶ Cloudflare Pages（静态前端，不限流量）
              │  fetch → https://api.你的域名/api
              ▼
         Oracle 免费机器 / GCP e2-micro / Render（后端）
              │
              ▼
         免费 MySQL（TiDB Cloud Starter / OCI MySQL HeatWave）
```

### 4.1 前端放 Cloudflare Pages

1. Cloudflare 控制台 → Workers & Pages → 创建 Pages → 连你的 GitHub 仓库
2. 构建配置：**构建命令留空**，**输出目录填 `front`**（本项目零构建）
3. 部署完拿到 `xxx.pages.dev`，可以绑自己的域名

**关键一步**：`front/config.js` 里把后端地址写成完整 URL（打包进 Pages 的是静态文件，没有 Nginx 帮你做替换了）：

```js
window.__API_BASE__ = 'https://api.你的域名/api';
window.__USE_CACHE__ = false;   // 后端没装 Redis 就保持 false
```

### 4.2 跨域：开箱可用，但建议收紧

后端默认 `allowedOriginPatterns("*")`，所以 Pages 域名访问接口**不需要改任何配置**。
但默认值等于「任何网站都能带着用户 Cookie 调你的接口」，生产建议收紧：

```bash
# deploy/.env
CORS_ALLOWED_ORIGIN_PATTERNS=https://你的pages域名.pages.dev,https://你的域名
```

### 4.3 免费 MySQL 用哪个

| 选项 | 免费额度 | 说明 |
| --- | --- | --- |
| **TiDB Cloud Starter** | 每组织可建若干免费实例，5GiB 级存储 + 每月千万级 RU（额度以官网为准） | **MySQL 协议兼容**，有新加坡区（国内延迟可接受）；**必须开 SSL** |
| **OCI MySQL HeatWave Always Free** | 50GB 存储 + 50GB 备份 | 在 Oracle 云里，和 Oracle 机器内网互通，延迟最低 |
| ⚠️ 各种「免费 30 天」的数据库 | — | **别用**。到期直接删库，等于把数据放别人手上掐着 |

用 TiDB 时，JDBC 连接串要记得开 SSL（项目默认 `useSSL=false`）：

```
jdbc:mysql://<tidb-host>:4000/evidence?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=true&requireSSL=true
```

另外建库脚本里用到 `tinyint unsigned`、`ON UPDATE CURRENT_TIMESTAMP` 这些写法，
导入前先在 TiDB 上跑一遍 `deploy/mysql/init.sql`（几分钟的事），确认没有兼容报错。

---

## 五、影像存哪：这才是长期成本所在

影像是这套系统里**唯一会持续产生成本**的东西。三个选择：

| 方案 | 成本 | 改造量 |
| --- | --- | --- |
| **腾讯云 COS（现状）** | 新用户有免费额度（额度和期限以官网为准），到期后按量付费。个人平台量级通常每月几毛到几元 | **零改造** |
| **Cloudflare R2** | **10GB 存储免费**，出网费 **$0**（这是它最狠的地方）。10GB 大约能放 2 万张压缩图 | 要改代码：上传、删除、取主色调（`imageAve`）三处，因为项目用的是腾讯云 COS SDK，R2 是 S3 协议 |
| 直接存服务器硬盘 | 免费但吃 40G 磁盘，且失去 CDN 和影像处理能力 | 要改代码，不推荐 |

**建议**：先继续用 COS（量小很便宜，几乎无感），等影像真的多到几万张、或者免费额度到期了，再考虑迁 R2。需要迁移的话告诉我，我可以把这三个地方改成 S3 兼容实现。

---

## 六、免费域名与 HTTPS

| 方式 | 成本 | 说明 |
| --- | --- | --- |
| **Cloudflare 免费 DNS + Universal SSL** | 域名钱（约 ¥35~50/年）+ $0 | 域名 NS 托管到 Cloudflare，证书自动签发、自动续期，还能顺带白拿 CDN 和 DDoS 防护 |
| Let's Encrypt + acme.sh | $0 | 完全免费、自动续期；本项目的 `deploy/nginx/https.conf` 已经配好 443，换证书路径即可 |
| 腾讯云 / 阿里云免费证书 | $0 | **有效期 3 个月**（2024 年起从 1 年缩短），单域名、不支持泛域名，每年额度有限（腾讯云 20~50 张 / 阿里云 20 张），需手动重新申请 |
| 不买域名 | $0 | 用 `http://<IP>:8080` 访问（记得 `.env` 里设 `HTTP_PORT=8080`）|

> 不建议用 nip.io 这类免费通配域名去签正式证书，稳定性没保障。

---

## 七、⚠️ 免费层的纪律

1. **免费层是营销预算，不是合同。** Oracle 已经砍过一次额度且没有公告；Koyeb、Fly.io、Heroku 的免费层说没就没。
2. **数据必须自己备份，而且要异地。** 免费机器被回收、数据库被删，都是真实发生过的事：
   ```bash
   # 服务器上跑（建议 crontab 每天）
   bash deploy/backup.sh
   # 再把备份拉回本地一份
   scp root@<服务器IP>:/var/backups/evidence/*.sql.gz ./
   ```
3. **别把唯一副本放在免费层**。练手项目无所谓，一旦有真东西，要么付费，要么本地留副本。
4. **额度要盯着**：TiDB 的 RU、R2 的 10GB、COS 的免费额度，超了会扣费或停服。

---

## 八、怎么选

| 你的情况 | 选哪个 |
| --- | --- |
| 想长期稳定、机器归自己、国内访问快 | **方案 A：Oracle Always Free** |
| 只想零成本跑起来给人看 | **方案 B：Pages + Oracle/GCP + TiDB** |
| 只是演示/交作业，几天后就撤 | 方案 C：Render（接受 30 秒冷启动） |
| 不想折腾、每年几十块能接受 | 就用[腾讯云轻量那套](deploy.md)，几十元/年，最省心 |

---

## 九、命令速查

```bash
# ---------- Oracle / GCP 等自建机器 ----------
# 低配模式（1~2G 内存，无 Redis）
docker compose --env-file deploy/.env -f deploy/docker-compose.free.yml up -d --build

# 完整模式（≥2G 内存，带 Redis 缓存）
docker compose --env-file deploy/.env -f deploy/docker-compose.yml up -d --build

# 健康检查（未登录返回 code 40100 就说明后端通了）
curl -s http://127.0.0.1:${HTTP_PORT:-80}/api/user/get/login

# 确认前端拿到的后端地址是对的（最容易静默失败的一步）
curl -s http://127.0.0.1:${HTTP_PORT:-80}/config.js | grep __API_BASE__

# 数据库备份
bash deploy/backup.sh
```

---

## 十、这份指南里哪些是验证过的

**实测验证过**：
- Redis 缺失时应用能正常启动、除缓存接口外功能正常（免 Redis 部署可行）
- 默认跨域配置允许前后端分离部署（`allowedOriginPatterns("*")` + 允许携带 Cookie）
- 修正后的 `deploy/mysql/init.sql` 能建出与实体完全一致的库表
- `docker-compose.free.yml` 与 `default.no-cache.conf` 的语法与结构校验通过

**没法替你验证（我没有这些平台的账号，或本机没有 Docker）**：
- Oracle / GCP / Render 上的真实部署效果
- 基础镜像的 arm64 变体（已给出自检命令，见 3.2）
- TiDB Cloud 对本项目建表语句的兼容性（建议导入前先跑一遍）

> 需要我针对你实际选的平台，把部署命令和配置再具体化一层（比如帮你写 Render 的 `render.yaml`
> 或 Cloudflare Pages 的构建配置），说一声就行。
