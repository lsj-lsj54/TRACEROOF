# 部署手册 · 阿里云 ECS（8.163.123.58）

> 这是「证链 TraceProof」在当前生产服务器的部署与更新备忘，供下次改代码后快速上线。
> 只记录操作流程，不记录任何密码或密钥。密钥在服务器 `deploy/.env`（权限 600），不在仓库里。

## 一、当前部署环境

| 项 | 值 |
| --- | --- |
| 公网 IP | `8.163.123.58` |
| 系统 | Ubuntu 22.04 64 位 |
| CPU / 内存 | 2 核 / 约 1.7 GB（已加 2 GB swap） |
| SSH | `root@8.163.123.58`（端口 22） |
| 项目目录 | `/root/evidence` |
| 部署模式 | `free`（精简资源编排，含必需 Redis） |
| 对外端口 | `80`（已备案，安全组已放行 TCP 80） |
| 镜像标签 | 跟随 git 短 SHA（当前 `bcbebd1`） |
| 容器 | `evidence-app` / `evidence-front` / `evidence-mysql` / `evidence-redis` |

- swap 已写入 `/etc/fstab`，重启不丢失。
- 后端镜像构建走阿里云 Maven 镜像源（见 `Dockerfile`），国内构建约 7 分钟。

## 二、更新并重新部署（核心流程）

### 1. 本地：改代码 → 提交 → 推送

```bash
git add -A
git commit -m "你的改动说明"
git push origin master
```

> 不要提交 `deploy/.env`、`application-local.yml`（已在 `.gitignore`）。它们只存在于对应机器上。

### 2. 服务器：拉代码 → 部署

```bash
ssh root@8.163.123.58
cd /root/evidence
git pull --depth 1 origin master
bash deploy/deploy.sh
```

`deploy.sh` 会自动：按 git SHA 打镜像标签 → 校验 Nginx 配置 → 构建并启动 → 健康检查 → 失败自动回滚到上一版本。

### 3. 验证

```bash
# 首页
curl -s -o /dev/null -w "HTTP %{http_code}\n" http://127.0.0.1/

# 后端（未登录应返回 code 40100）
curl -s http://127.0.0.1/api/user/get/login

# 公共列表（code 0 即数据库查询链路正常）
curl -s -X POST http://127.0.0.1/api/evidence/list/page/vo \
  -H "Content-Type: application/json" \
  -d '{"current":1,"pageSize":12,"archiveId":0}'
```

浏览器打开 `http://8.163.123.58/` 最终确认。

## 三、回滚

- 正常情况 `deploy.sh` 健康检查失败会自动回滚到上一镜像，并保留失败镜像 `evidence-app:<sha>.failed`。
- 手动回滚到上一个好版本：

```bash
cd /root/evidence
# 上一个好标签记录在 deploy/.last-good-tag
cat deploy/.last-good-tag
EVIDENCE_TAG=<旧标签> docker compose --env-file deploy/.env \
  -f deploy/docker-compose.free.yml up -d app front
```

## 四、常用运维命令

```bash
cd /root/evidence

# 看容器状态
docker compose --env-file deploy/.env -f deploy/docker-compose.free.yml ps

# 看后端日志（实时）
docker logs -f evidence-app

# 重启单个服务
docker restart evidence-app

# 数据库备份（按天保留，见 deploy/backup.sh）
bash deploy/backup.sh
```

## 五、修改环境变量 / 密钥

`deploy/.env` 在服务器 `/root/evidence/deploy/.env`，被 `.gitignore` 排除，更新代码不会覆盖它。

需要改数据库密码、Redis 密码、COS 或跨域时：

```bash
vi /root/evidence/deploy/.env
cd /root/evidence
docker compose --env-file deploy/.env -f deploy/docker-compose.free.yml up -d
```

改完 COS 密钥后重启后端生效：

```bash
docker restart evidence-app
```

## 六、管理员账号

1. 网页注册一个普通账号。
2. 设为管理员：

```bash
docker exec -it evidence-mysql mysql -uroot -p evidence -e \
  "UPDATE user SET userRole='admin' WHERE userAccount='你的账号';"
```

（密码见 `deploy/.env` 的 `MYSQL_ROOT_PASSWORD`。）

## 七、注意事项

- **root 密码曾在对话中出现过**，上线后建议在阿里云控制台「重置实例密码」更换。
- 安全组需放行：TCP 22（SSH）、TCP 80（HTTP）；以后上 HTTPS 再加 443。
- 内存仅 1.7 GB，不要在服务器上跑 Maven 之外的重型进程；依赖已在镜像构建阶段完成。
- 精简编排仍包含 Redis（登录会话、锁、限流、缓存都依赖它），不要删。
- 若服务器磁盘或镜像堆积，可执行 `docker image prune -f --filter "until=168h"`。
