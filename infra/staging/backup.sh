#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"
set -a; . ./.env; set +a

DATABASES=(user_db catalog_db commerce_db auction_db inventory_db notification_db payment_db shipment_db rating_db auth_db)
TARGET="${1:-backups/$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$TARGET"
umask 077

for db in "${DATABASES[@]}"; do
  docker compose exec -T postgres pg_dump -U "$DB_USERNAME" -d "$db" -Fc > "$TARGET/$db.dump"
  printf '%-16s %s\n' "$db" "$(du -h "$TARGET/$db.dump" | cut -f1)"
done
echo "dumps written to $TARGET"
