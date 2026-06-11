-- customer 도메인 초기 스키마 (구매자 전용)
-- 설계 DDL(sapari_postgres.sql) 기반, Postgres 타입 변환(datetime→timestamptz, tinyint(1)→boolean).
-- 도메인 간 참조는 물리 FK 없이 uuid id 컬럼(프로젝트 규칙).
CREATE SCHEMA IF NOT EXISTS customer_schema;

-- 구매자 배송지 목록. 기본 배송지 1개 지정 가능
CREATE TABLE customer_schema.user_addresses (
    id             uuid         NOT NULL DEFAULT gen_random_uuid(),
    user_id        uuid         NOT NULL,                -- ref: user_schema.users.id
    alias          varchar(50)  NOT NULL,
    recipient_name varchar(50)  NOT NULL,                -- 암호화
    phone          varchar(20)  NOT NULL,                -- 암호화
    address        varchar(255) NOT NULL,                -- 암호화
    address_detail varchar(100),                         -- 암호화
    postal_code    varchar(10)  NOT NULL,
    is_default     boolean      NOT NULL DEFAULT false,
    created_at     timestamptz  NOT NULL,
    updated_at     timestamptz  NOT NULL,
    CONSTRAINT pk_user_addresses PRIMARY KEY (id)
);
CREATE INDEX ON customer_schema.user_addresses (user_id, is_default);

-- 사용자 찜 목록. 재입고 알림 트리거 기준
CREATE TABLE customer_schema.wishlists (
    id         uuid        NOT NULL DEFAULT gen_random_uuid(),
    user_id    uuid        NOT NULL,                     -- ref: user_schema.users.id
    product_id uuid        NOT NULL,                     -- ref: product_schema.products.id
    created_at timestamptz NOT NULL,
    CONSTRAINT pk_wishlists PRIMARY KEY (id)
);
CREATE UNIQUE INDEX ON customer_schema.wishlists (user_id, product_id);

-- 회원 등급 변경 이력. 최근 12개월 실결제 기준 산정
CREATE TABLE customer_schema.user_grade_histories (
    id             uuid        NOT NULL DEFAULT gen_random_uuid(),
    user_id        uuid        NOT NULL,                 -- ref: user_schema.users.id
    previous_grade varchar(30),
    new_grade      varchar(30) NOT NULL,
    base_amount    integer     NOT NULL,
    calculated_at  timestamptz NOT NULL,
    CONSTRAINT pk_user_grade_histories PRIMARY KEY (id)
);
CREATE INDEX ON customer_schema.user_grade_histories (user_id, calculated_at);

-- Transactional Outbox (customer 도메인 전용 — 비즈니스 INSERT와 같은 트랜잭션)
CREATE TABLE customer_schema.outbox_events (
    id             bigserial    NOT NULL,
    aggregate_type varchar(50)  NOT NULL,
    aggregate_id   uuid         NOT NULL,
    event_type     varchar(50)  NOT NULL,
    payload        jsonb        NOT NULL,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    processed_at   timestamptz,
    retry_count    integer      NOT NULL DEFAULT 0,
    last_error     text,
    CONSTRAINT pk_customer_outbox_events PRIMARY KEY (id)
);
CREATE INDEX ON customer_schema.outbox_events (processed_at, created_at);
CREATE INDEX ON customer_schema.outbox_events (aggregate_type, aggregate_id);
CREATE INDEX ON customer_schema.outbox_events (event_type, created_at);
CREATE INDEX ON customer_schema.outbox_events (retry_count, created_at);

-- 감사 로그 (customer 도메인 전용)
CREATE TABLE customer_schema.audit_logs (
    id          uuid         NOT NULL DEFAULT gen_random_uuid(),
    actor_id    uuid,
    actor_type  varchar(10)  NOT NULL,
    action      varchar(100) NOT NULL,
    target_id   uuid,
    target_type varchar(50),
    detail      jsonb,
    ip_address  varchar(45),
    created_at  timestamptz  NOT NULL,
    CONSTRAINT pk_customer_audit_logs PRIMARY KEY (id)
);
CREATE INDEX ON customer_schema.audit_logs (actor_id, actor_type, created_at);
CREATE INDEX ON customer_schema.audit_logs (action, created_at);
