-- 정리 스케줄러 분산 락 저장소 (ShedLock, JDBC 프로바이더) [SPR-142]
--
-- live-app 이 여러 레플리카로 뜨면 정리 잡 3종이 같은 회차를 동시에 돈다. DB 전이가 있는 두 잡은
-- 행 잠금이 막아주지만 orphan-media 는 DB 를 읽기만 하고 LiveKit 삭제/중단으로 바로 나가므로
-- 임계구역이 없다. 이 테이블이 그 임계구역이다.
--
-- 테이블 이름·컬럼은 ShedLock JdbcTemplateLockProvider 가 요구하는 스키마 그대로다 — 바꾸면 붙지 않는다.
-- 스키마를 새로 파지 않고 live_schema 에 두는 이유: 이 락을 쓰는 잡이 live 도메인 소유이고,
-- 스키마 추가는 infra/migration/migrate.sh 의 SCHEMAS 목록까지 건드리는 더 큰 변경이다.
--
-- ⚠️ 시각 컬럼만 저장소 관례(timestamptz)를 따르지 않는다. ShedLock 은 usingDbTime() 에서 락 시각을
--    timezone('utc', CURRENT_TIMESTAMP) 로 쓰고 또 그 값으로 만료를 비교하는데, 이건 무시간대
--    timestamp 다. 이걸 timestamptz 컬럼에 넣으면 <b>커넥션의 세션 타임존</b> 기준으로 해석되고,
--    pgjdbc 는 세션 타임존을 JVM 기본값으로 맞춘다 — 즉 저장되는 절대 시각이 인스턴스의 TZ 에 따라
--    달라진다. 같은 순간에 두 세션이 넣은 값을 실측하면 이렇다:
--
--      timestamptz : UTC 세션 2026-09-04 16:36:23+00 / KST 세션 2026-09-04 07:36:23+00  → 32400초 차이
--      timestamp   : 양쪽 모두 2026-09-04 16:36:23                                      → 차이 없음
--
--    TZ 가 다른 인스턴스가 섞이면 서로의 락을 만료로 보거나 영원히 유효로 봐서 상호배제가 조용히
--    깨진다. timestamp 는 오프셋에 영향받지 않아 그 경로가 아예 없다(ShedLock 공식 DDL 도 이쪽이다).
--    usingDbTime() 자체는 유지한다 — 만료 판정을 DB 시계로 하므로 인스턴스 간 시계 오차는 무관하다.
CREATE TABLE live_schema.shedlock (
    name       varchar(64)  NOT NULL,
    lock_until timestamp    NOT NULL,   -- UTC. 위 주석 참고 — timestamptz 로 바꾸지 말 것
    locked_at  timestamp    NOT NULL,   -- UTC
    locked_by  varchar(255) NOT NULL,
    CONSTRAINT pk_live_shedlock PRIMARY KEY (name)
);
