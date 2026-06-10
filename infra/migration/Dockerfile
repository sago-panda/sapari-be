# =============================================================================
# sapari-be 마이그레이션 이미지 — flyway CLI + 스키마별 SQL + per-schema 러너
#
# 빌드 (리포 루트에서 — db/migration COPY 때문에 컨텍스트가 루트여야 함):
#   docker build -f infra/migration/Dockerfile -t sapari-migration:<tag> .
#
# 실행 (반드시 PRIMARY에만):
#   docker run --rm \
#     -e DB_URL=jdbc:postgresql://<primary-host>:5432/sapari_db \
#     -e DB_USER=... -e DB_PASSWORD=... \
#     sapari-migration:<tag>
#
# 운영: 같은 이미지를 Helm pre-upgrade Job이 실행 (DB 자격증명은 k8s Secret 주입,
#       DDL 권한의 flyway 전용 롤 사용 — 앱 롤과 분리)
# =============================================================================
FROM flyway/flyway:10-alpine

# 스키마별 마이그레이션 SQL (소유: 각 도메인. 레이아웃: db/migration/<schema>/V*.sql)
COPY db/migration /flyway/db/migration

# per-schema 러너 (스키마마다 자기 history + 독립 버전 + outOfOrder)
COPY infra/migration/migrate.sh /migrate.sh
RUN chmod +x /migrate.sh

ENTRYPOINT ["/migrate.sh"]
