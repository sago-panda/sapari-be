-- settlement 도메인 초기 스키마 (판매자 정산 — 불변 원장, batch-app 구동)
-- 설계 DDL(sapari_postgres.sql) 기반, Postgres 타입 변환.
-- 계좌·금액·구매확정은 스냅샷으로 자급자족(order/seller에 물리 FK·JOIN 없음).
CREATE SCHEMA IF NOT EXISTS settlement_schema;

-- 판매자 정산 불변 원장. 영구 오류 시 FAILED 후 새 정산, 일시 오류 시 재시도
CREATE TABLE settlement_schema.settlements (
    id                      uuid         NOT NULL DEFAULT gen_random_uuid(),
    seller_id               uuid         NOT NULL,       -- ref: user_schema.users.id
    period_start            date         NOT NULL,
    period_end              date         NOT NULL,
    gross_amount            integer      NOT NULL,
    commission_rate         numeric(5,2) NOT NULL,
    commission_amount       integer      NOT NULL,
    coupon_discount_total   integer      NOT NULL DEFAULT 0,
    net_amount              integer      NOT NULL,
    status                  varchar(15)  NOT NULL DEFAULT 'PENDING',
    bank_name_snapshot      varchar(50)  NOT NULL,
    account_number_snapshot varchar(100) NOT NULL,       -- 암호화
    account_holder_snapshot varchar(50)  NOT NULL,       -- 암호화
    failure_type            varchar(20),
    retry_count             integer      NOT NULL DEFAULT 0,
    note                    text,
    settled_at              timestamptz,
    created_at              timestamptz  NOT NULL,
    updated_at              timestamptz  NOT NULL,
    CONSTRAINT pk_settlements PRIMARY KEY (id)
);
CREATE INDEX ON settlement_schema.settlements (seller_id, period_start);

-- 정산 단위 라인. order_item별 금액 분해, 쿠폰 발급자 부담 할인액 포함
CREATE TABLE settlement_schema.settlement_items (
    id                    uuid        NOT NULL DEFAULT gen_random_uuid(),
    settlement_id         uuid        NOT NULL,
    order_item_id         uuid        NOT NULL,          -- ref: order_schema.order_items.id
    gross_amount          integer     NOT NULL,
    commission_amount     integer     NOT NULL,
    coupon_discount_share integer,
    net_amount            integer     NOT NULL,
    purchase_confirmed_at timestamptz NOT NULL,          -- 스냅샷
    auto_confirmed        boolean     NOT NULL,          -- 스냅샷
    created_at            timestamptz NOT NULL,
    CONSTRAINT pk_settlement_items PRIMARY KEY (id)
);
CREATE INDEX ON settlement_schema.settlement_items (settlement_id);
CREATE INDEX ON settlement_schema.settlement_items (order_item_id);

-- Transactional Outbox (settlement 도메인 전용 — 정산 완료/실패 이벤트)
CREATE TABLE settlement_schema.outbox_events (
    id             bigserial    NOT NULL,
    aggregate_type varchar(50)  NOT NULL,
    aggregate_id   uuid         NOT NULL,
    event_type     varchar(50)  NOT NULL,
    payload        jsonb        NOT NULL,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    processed_at   timestamptz,
    retry_count    integer      NOT NULL DEFAULT 0,
    last_error     text,
    CONSTRAINT pk_settlement_outbox_events PRIMARY KEY (id)
);
CREATE INDEX ON settlement_schema.outbox_events (processed_at, created_at);
CREATE INDEX ON settlement_schema.outbox_events (aggregate_type, aggregate_id);
CREATE INDEX ON settlement_schema.outbox_events (event_type, created_at);
CREATE INDEX ON settlement_schema.outbox_events (retry_count, created_at);

-- 감사 로그 (settlement 도메인 전용 — 금융 원장이라 감사 중요)
CREATE TABLE settlement_schema.audit_logs (
    id          uuid         NOT NULL DEFAULT gen_random_uuid(),
    actor_id    uuid,
    actor_type  varchar(10)  NOT NULL,
    action      varchar(100) NOT NULL,
    target_id   uuid,
    target_type varchar(50),
    detail      jsonb,
    ip_address  varchar(45),
    created_at  timestamptz  NOT NULL,
    CONSTRAINT pk_settlement_audit_logs PRIMARY KEY (id)
);
CREATE INDEX ON settlement_schema.audit_logs (actor_id, actor_type, created_at);
CREATE INDEX ON settlement_schema.audit_logs (action, created_at);
