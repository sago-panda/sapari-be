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
# 10-alpine 은 amd64 전용이라 arm64(Graviton) 노드에서 exec format error 로 죽는다.
# flyway/flyway:10 은 amd64·arm64 를 모두 담은 manifest list 다.
FROM flyway/flyway:10

# 스키마별 마이그레이션 SQL (소유: 각 도메인. 레이아웃: db/migration/<schema>/V*.sql)
COPY db/migration /flyway/db/migration

# per-schema 러너 (스키마마다 자기 history + 독립 버전 + outOfOrder)
# RUN chmod 대신 COPY --chmod — CI 는 amd64 러너에서 arm64 로 빌드하므로 RUN 이 있으면
# 에뮬레이션(binfmt)이 필요해진다(.gitlab/ci/image.yml 아키텍처 문단).
COPY --chmod=0755 infra/migration/migrate.sh /migrate.sh

ENTRYPOINT ["/migrate.sh"]
