#!/usr/bin/env bash
#
# 一键部署 / 更新：校验 → 构建 → 启动 → 健康检查 → 失败自动回滚
#
# 用法（在服务器上的项目根目录执行）：
#   bash deploy/deploy.sh
#
# 它做了什么：
#   1. 检查 .env、docker、compose 是否就绪，并拦住没改过的占位密码；
#   2. 用 git 短 SHA 作为镜像标签（可追溯，不用 latest）；
#   3. 先单独校验一遍 Nginx 配置（配置写错会 fail fast，不必等容器起不来）；
#   4. 构建并启动全部服务；
#   5. 轮询后端接口做健康检查，通过才算发布成功；
#   6. 健康检查失败 → 打印日志 → 自动把镜像回滚到上一版本。
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
ENV_FILE="$SCRIPT_DIR/.env"

# 部署模式：full（MySQL + Redis + 后端 + 前端）/ free（免 Redis 的低配编排，适合 1~2G 小机器）
# 可用环境变量显式指定：DEPLOY_MODE=free bash deploy/deploy.sh
# 不指定时按机器内存自动判断（小于 3G 走 free）。
DEPLOY_MODE="${DEPLOY_MODE:-auto}"

detect_total_mem_mb() {
  if [ -r /proc/meminfo ]; then
    awk '/MemTotal/ {printf "%d", $2/1024}' /proc/meminfo 2>/dev/null || echo 0
  else
    echo 0
  fi
}

if [ "$DEPLOY_MODE" = "auto" ]; then
  MEM_MB="$(detect_total_mem_mb)"
  if [ "${MEM_MB:-0}" -gt 0 ] && [ "${MEM_MB:-0}" -lt 3000 ]; then
    DEPLOY_MODE="free"
  else
    DEPLOY_MODE="full"
  fi
fi

case "$DEPLOY_MODE" in
  free)
    COMPOSE_FILE="$SCRIPT_DIR/docker-compose.free.yml"
    # 低配编排没有 Redis，前端必须用不打开缓存开关的 Nginx 配置
    NGINX_CONF="$SCRIPT_DIR/nginx/default.no-cache.conf"
    ;;
  full)
    COMPOSE_FILE="$SCRIPT_DIR/docker-compose.yml"
    NGINX_CONF="$SCRIPT_DIR/nginx/default.conf"
    ;;
  *)
    printf '\033[31m[错误]\033[0m DEPLOY_MODE 只能是 auto / free / full，当前是：%s\n' "$DEPLOY_MODE" >&2
    exit 1
    ;;
esac

log()  { printf '\033[36m[%s]\033[0m %s\n' "$(date '+%H:%M:%S')" "$*"; }
warn() { printf '\033[33m[警告]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[31m[错误]\033[0m %s\n' "$*" >&2; exit 1; }

log "部署模式：$DEPLOY_MODE（编排 $(basename "$COMPOSE_FILE")，Nginx $(basename "$NGINX_CONF")）"
if [ "$DEPLOY_MODE" = "full" ]; then
  MEM_MB="$(detect_total_mem_mb)"
  if [ "${MEM_MB:-0}" -gt 0 ] && [ "${MEM_MB:-0}" -lt 3000 ]; then
    warn "机器只有约 ${MEM_MB}MB 内存，跑完整版（含 Redis）可能吃紧；"
    warn "建议改用低配模式：DEPLOY_MODE=free bash deploy/deploy.sh"
  fi
fi

compose() { docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"; }

# ---------------------------------------------------------------- 1. 前置检查
[ -f "$ENV_FILE" ] || die "缺少 $ENV_FILE。
  本机生成（会自动读 application-local.yml 里的 COS 参数 + 随机生成密码）：
     bash deploy/make-env.sh
  或手动：cp deploy/.env.example deploy/.env 然后逐项填写"
command -v docker >/dev/null 2>&1 || die "未安装 docker（Ubuntu：curl -fsSL https://get.docker.com | sh）"
docker compose version >/dev/null 2>&1 || die "缺少 docker compose v2 插件，请升级 Docker 到 20.10+"

if grep -qE '^(MYSQL_ROOT_PASSWORD|MYSQL_PASSWORD|REDIS_PASSWORD)=change-me' "$ENV_FILE"; then
  die "$ENV_FILE 里还有 change-me 占位密码，请改成真实强密码后再发布"
fi
for key in MYSQL_PASSWORD COS_HOST COS_SECRET_ID COS_SECRET_KEY COS_REGION COS_BUCKET; do
  if ! grep -qE "^${key}=.+" "$ENV_FILE"; then
    die "$ENV_FILE 缺少 $key，缺了会导致后端连不上数据库或启动失败"
  fi
done

HTTP_PORT="$(grep -E '^HTTP_PORT=' "$ENV_FILE" | head -1 | cut -d= -f2 | tr -d ' \r')"
HTTP_PORT="${HTTP_PORT:-80}"

# ------------------------------------------------------------ 2. 版本与回滚点
TAG="$(git -C "$ROOT_DIR" rev-parse --short HEAD 2>/dev/null || echo "manual-$(date +%Y%m%d-%H%M)")"
# 记录当前正在跑的镜像，作为回滚点
PREV_IMAGE="$(docker inspect -f '{{.Config.Image}}' evidence-app 2>/dev/null || true)"
[ -n "$PREV_IMAGE" ] && log "当前线上镜像：$PREV_IMAGE"

# ------------------------------------------------------------- 3. 配置预校验
log "校验 Nginx 配置"
# --add-host 是必需的：配置里写的是 proxy_pass http://app:8666，服务名 "app" 只在
# compose 网络内可解析，而这个校验容器跑在默认网桥上。nginx 在加载配置时就要解析
# upstream 主机名，不注入这条记录会误报 [emerg] host not found in upstream "app"，
# 把一份本来就合法的配置判成语法错误。
# 以后若在 nginx 配置里新增了别的 upstream 服务名，这里要同步补 --add-host。
docker run --rm --add-host app:127.0.0.1 \
  -v "$NGINX_CONF:/etc/nginx/conf.d/default.conf:ro" nginx:1.27-alpine nginx -t \
  || die "Nginx 配置有语法错误，已中止发布（上面的输出指出了具体行号）"

# --------------------------------------------------------------- 4. 构建启动
log "构建并启动（tag=$TAG，端口=$HTTP_PORT）"
PICTURE_TAG="$TAG" compose up -d --build || die "构建或启动失败，执行 deploy.sh 前请确认 Docker 守护进程正常"

# ------------------------------------------------------------- 5. 健康门禁
# 后端 /api/user/get/login 未登录时会返回 {"code":40100,...}，能拿到 JSON 就说明应用已就绪
wait_healthy() {
  local i out
  for i in $(seq 1 40); do
    if out="$(curl -fsS -m 3 "http://127.0.0.1:${HTTP_PORT}/api/user/get/login" 2>/dev/null)"; then
      case "$out" in
        *'"code"'*) return 0 ;;
      esac
    fi
    sleep 3
  done
  return 1
}

log "等待服务就绪（最多 2 分钟）"
if wait_healthy; then
  log "发布成功 ✅  tag=$TAG  地址=http://<服务器IP>:${HTTP_PORT}/"
  # 记下这次成功的版本，便于人工查看
  printf '%s\n' "$TAG" > "$SCRIPT_DIR/.last-good-tag"
  # 顺手清理 7 天前的悬空镜像，避免磁盘被旧镜像吃掉
  docker image prune -f --filter "until=168h" >/dev/null 2>&1 || true
  compose ps
  exit 0
fi

# --------------------------------------------------------------- 6. 自动回滚
printf '\n'
warn "健康检查失败，最近的应用日志如下："
compose logs --tail=80 app || true

if [ -z "$PREV_IMAGE" ]; then
  die "没有可回滚的上一版本，请根据上面的日志排查（常见原因见 docs/deploy.md「排障清单」）"
fi

log "开始回滚到 $PREV_IMAGE"
# 保留现场：把这次构建出来的镜像另存一个 tag，方便事后分析
docker tag "evidence-app:$TAG" "evidence-app:$TAG.failed" 2>/dev/null || true
docker tag "$PREV_IMAGE" "evidence-app:$TAG"
PICTURE_TAG="$TAG" compose up -d app

if wait_healthy; then
  die "已回滚到 $PREV_IMAGE 并恢复服务；可疑镜像保留为 evidence-app:$TAG.failed，请据此排查"
fi
die "回滚后仍未通过健康检查，请立即人工介入（查看：compose logs -f app）"
