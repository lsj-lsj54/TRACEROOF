#!/usr/bin/env bash
#
# 数据库备份：导出 → 压缩 → 按保留期清理 → 可选上传到腾讯云 COS
#
# 用法：
#   bash deploy/backup.sh
#
# 建议挂到 crontab 每天凌晨跑一次：
#   0 3 * * * cd /root/evidence && bash deploy/backup.sh >> /var/log/evidence-backup.log 2>&1
#
# 注意：只有数据库在这里备份。图片本体在腾讯云 COS 上，
#      建议同时开启 COS 的「版本控制」或生命周期跨地域复制，避免误删。
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$SCRIPT_DIR/.env"

log()  { printf '\033[36m[%s]\033[0m %s\n' "$(date '+%H:%M:%S')" "$*"; }
warn() { printf '\033[33m[警告]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[31m[错误]\033[0m %s\n' "$*" >&2; exit 1; }

[ -f "$ENV_FILE" ] || die "缺少 $ENV_FILE"
# 只取需要的键，避免把整个 .env 里的特殊字符带进 shell
BACKUP_DIR="$(grep -E '^BACKUP_DIR=' "$ENV_FILE" | cut -d= -f2 | tr -d ' \r')"
KEEP_DAYS="$(grep -E '^BACKUP_KEEP_DAYS=' "$ENV_FILE" | cut -d= -f2 | tr -d ' \r')"
BACKUP_DIR="${BACKUP_DIR:-/var/backups/evidence}"
KEEP_DAYS="${KEEP_DAYS:-7}"

mkdir -p "$BACKUP_DIR"
STAMP="$(date '+%Y%m%d-%H%M%S')"
OUT="$BACKUP_DIR/evidence-$STAMP.sql.gz"

log "导出数据库到 $OUT"
# --single-transaction：不锁表（InnoDB），线上可安全执行
# --routines --triggers：把存储过程/触发器一起带上
docker exec evidence-mysql sh -c \
  'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines --triggers --default-character-set=utf8mb4 evidence' \
  | gzip > "$OUT"

# 备份文件必须非空，否则说明导出失败（gzip 头大约 20 字节）
SIZE="$(wc -c < "$OUT" | tr -d ' ')"
if [ "$SIZE" -lt 1024 ]; then
  die "备份文件异常小（${SIZE} 字节），导出大概率失败，请检查：docker logs evidence-mysql --tail 50"
fi
log "导出完成，大小 $((SIZE / 1024)) KB"

log "清理 $KEEP_DAYS 天前的旧备份"
find "$BACKUP_DIR" -name 'evidence-*.sql.gz' -type f -mtime "+$KEEP_DAYS" -print -delete || true

# 可选：上传到 COS（异地副本）。需要服务器上装了 coscli 并已配置密钥。
if command -v coscli >/dev/null 2>&1; then
  COS_BUCKET="$(grep -E '^COS_BUCKET=' "$ENV_FILE" | cut -d= -f2 | tr -d ' \r')"
  COS_REGION="$(grep -E '^COS_REGION=' "$ENV_FILE" | cut -d= -f2 | tr -d ' \r')"
  if [ -n "$COS_BUCKET" ]; then
    log "上传备份到 cos://$COS_BUCKET/backup/"
    if coscli cp "$OUT" "cos://$COS_BUCKET/backup/$(basename "$OUT")" -e "cos.$COS_REGION.myqcloud.com"; then
      log "已上传 COS"
    else
      # 上传失败不影响本地备份的有效性，只告警
      warn "上传 COS 失败，本地备份仍然可用：$OUT"
    fi
  fi
else
  echo "  提示：未检测到 coscli，跳过异地备份。"
  echo "        如需把备份也放到 COS，装一个：https://cloud.tencent.com/document/product/436/63144"
fi

log "备份完成：$OUT"
