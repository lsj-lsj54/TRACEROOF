#!/usr/bin/env bash
#
# 在**本机**生成 deploy/.env（省掉手抄 5 个 COS 密钥 + 想密码的步骤）
#
# 用法（在项目根目录执行）：
#   bash deploy/make-env.sh            # 生成 deploy/.env
#   bash deploy/make-env.sh --force    # 覆盖已存在的 .env
#
# 它会：
#   1. 从 src/main/resources/application-local.yml 读出腾讯云 COS 的 5 个参数
#   2. 随机生成 MySQL / Redis 的强密码（不含会破坏 .env 解析的特殊字符）
#   3. 写入 deploy/.env 并 chmod 600
#
# 生成后把它传到服务器：
#   scp deploy/.env root@<服务器IP>:/root/evidence/deploy/.env
# 服务器上再 chmod 600。
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
LOCAL_YML="$ROOT_DIR/src/main/resources/application-local.yml"
OUT="$SCRIPT_DIR/.env"

log() { printf '\033[36m%s\033[0m\n' "$*"; }
die() { printf '\033[31m[错误]\033[0m %s\n' "$*" >&2; exit 1; }

FORCE=0
[ "${1:-}" = "--force" ] && FORCE=1

if [ -f "$OUT" ] && [ "$FORCE" -ne 1 ]; then
  die "$OUT 已存在。想重新生成请加 --force（会覆盖现有配置）"
fi

# 从 application-local.yml 的 cos.client 段里取值（只在该段内找，避免撞上 redis 的 host:）
get_cos() {
  awk '/^cos:/{f=1;next} f&&/^[^[:space:]]/{exit} f' "$LOCAL_YML" \
    | awk -v key="$1" '$1 == key":" {print $2; exit}'
}

COS_HOST_V=""; COS_ID_V=""; COS_KEY_V=""; COS_REGION_V=""; COS_BUCKET_V=""
if [ -f "$LOCAL_YML" ]; then
  COS_HOST_V="$(get_cos host)"
  COS_ID_V="$(get_cos secretId)"
  COS_KEY_V="$(get_cos secretKey)"
  COS_REGION_V="$(get_cos region)"
  COS_BUCKET_V="$(get_cos bucket)"
else
  log "⚠ 没找到 $LOCAL_YML（可能在服务器上或已被删除），COS 参数留空，稍后手动填"
fi

# 随机强密码：去掉 / + = 这类可能干扰 .env 解析的字符
gen_pass() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -base64 24 | tr -d '/+=' | cut -c1-20
  else
    LC_ALL=C tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 20
  fi
}

MYSQL_PW="$(gen_pass)"
REDIS_PW="$(gen_pass)"

cat > "$OUT" << EOF
# 由 deploy/make-env.sh 生成（$(date '+%Y-%m-%d %H:%M:%S')）
# 内含明文密钥，已在 .gitignore 里，不要提交；传服务器后记得 chmod 600。

# ---------- 站点 ----------
HTTP_PORT=80

# ---------- 数据库 ----------
MYSQL_ROOT_PASSWORD=$MYSQL_PW
MYSQL_USERNAME=root
MYSQL_PASSWORD=$MYSQL_PW

# ---------- Redis ----------
# 用 deploy/docker-compose.free.yml（免 Redis 低配编排）时这个值用不到，
# 但保留着，方便以后加到 4G 内存再切回完整版。
REDIS_PASSWORD=$REDIS_PW

# ---------- 腾讯云 COS（取自 application-local.yml）----------
COS_HOST=$COS_HOST_V
COS_SECRET_ID=$COS_ID_V
COS_SECRET_KEY=$COS_KEY_V
COS_REGION=$COS_REGION_V
COS_BUCKET=$COS_BUCKET_V

# ---------- 跨域 ----------
# 同源部署（Nginx 反代 /api）用不到跨域；想收紧就填自己的域名，多个用逗号分隔。
CORS_ALLOWED_ORIGIN_PATTERNS=*

# ---------- 备份（deploy/backup.sh 用）----------
BACKUP_KEEP_DAYS=7
BACKUP_DIR=/var/backups/evidence
EOF

chmod 600 "$OUT" 2>/dev/null || true

# 只打印掩码后的摘要，避免密钥出现在终端历史/日志里
mask() {
  local v="$1"
  if [ -z "$v" ]; then echo "(空)"; return; fi
  if [ "${#v}" -le 8 ]; then echo "****"; else echo "${v:0:4}****${v: -4}"; fi
}

log "已生成 $OUT（权限 600）"
echo
echo "  COS_HOST   = ${COS_HOST_V:-（空，需手填）}"
echo "  COS_SECRET_ID  = $(mask "$COS_ID_V")"
echo "  COS_SECRET_KEY = $(mask "$COS_KEY_V")"
echo "  COS_REGION = ${COS_REGION_V:-（空）}   COS_BUCKET = ${COS_BUCKET_V:-（空）}"
echo "  MySQL/Redis 密码已随机生成（查看：cat deploy/.env）"
echo
if [ -z "$COS_ID_V" ]; then
  echo "  ⚠ COS 参数为空，请手动补全后再部署，否则后端启动会失败。"
fi
echo "下一步：scp deploy/.env root@<服务器IP>:/root/evidence/deploy/.env"
