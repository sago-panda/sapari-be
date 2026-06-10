#!/bin/sh
# =============================================================================
# sapari-be 스키마별 독립 Flyway 마이그레이션 러너
#
# 스키마 = 모듈 = 자기 마이그레이션 소유:
#   - 스키마마다 자기 history(<schema>_schema.flyway_schema_history)와 독립 버전 시퀀스
#   - cross-schema FK가 없어 스키마 간 순서 의존 없음(루프 순서 무관)
#   - outOfOrder=true: V<yyyyMMddHHmm> 타임스탬프 버전이 브랜치 머지 순서와 어긋나도 적용
#
# 실행 (반드시 PRIMARY에만 — replica는 WAL로 자동 전파):
#   로컬:   DB_URL=jdbc:postgresql://localhost:5432/sapari_db DB_USER=postgres DB_PASSWORD=... \
#           ./infra/migration/migrate.sh        (flyway CLI 설치 시)
#   docker: infra/migration/Dockerfile로 빌드한 이미지 실행 (아래 Dockerfile 참고)
#   운영:   같은 이미지를 Helm pre-upgrade Job이 실행 (운영 진입 시 승격)
# =============================================================================
set -eu

DB_URL="${DB_URL:?DB_URL is required (jdbc:postgresql://<primary-host>:5432/<db>)}"
DB_USER="${DB_USER:?DB_USER is required}"
DB_PASSWORD="${DB_PASSWORD:?DB_PASSWORD is required}"

# SQL 루트: 컨테이너(이미지 COPY) 기본값, 로컬 실행 시 리포 루트의 db/migration 자동 탐지
MIGRATION_ROOT="${MIGRATION_ROOT:-/flyway/db/migration}"
if [ ! -d "$MIGRATION_ROOT" ]; then
    SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
    MIGRATION_ROOT="$SCRIPT_DIR/../../db/migration"
fi
[ -d "$MIGRATION_ROOT" ] || { echo "ERROR: migration root not found: $MIGRATION_ROOT" >&2; exit 1; }

FLYWAY_BIN="${FLYWAY_BIN:-flyway}"

# 폴더명:스키마명 (스키마 추가 시 여기에 한 줄 + db/migration/<폴더> 생성)
SCHEMAS="
user:user_schema
customer:customer_schema
seller:seller_schema
product:product_schema
review:review_schema
order:order_schema
promotion:promotion_schema
notification:notification_schema
settlement:settlement_schema
live:live_schema
"

for entry in $SCHEMAS; do
    folder="${entry%%:*}"
    schema="${entry##*:}"
    echo ""
    echo "=== migrate [$folder] -> $schema ==="
    "$FLYWAY_BIN" \
        -url="$DB_URL" \
        -user="$DB_USER" \
        -password="$DB_PASSWORD" \
        -schemas="$schema" \
        -defaultSchema="$schema" \
        -table="flyway_schema_history" \
        -locations="filesystem:$MIGRATION_ROOT/$folder" \
        -outOfOrder=true \
        migrate
done

echo ""
echo "=== all schemas migrated ==="
