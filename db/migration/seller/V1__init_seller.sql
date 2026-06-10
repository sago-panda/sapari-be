-- seller 도메인 초기 스키마
-- 출처: LocalCredentialEntity (BaseEntity 미상속 — created_at/updated_at 없음, PK 컬럼명 users_id).
CREATE SCHEMA IF NOT EXISTS seller_schema;

CREATE TABLE seller_schema.local_credentials (
    users_id           uuid         NOT NULL,
    password_hash      varchar(255) NOT NULL,
    failed_login_count integer      NOT NULL,
    locked_at          timestamptz,
    last_changed_at    timestamptz  NOT NULL,
    CONSTRAINT pk_local_credentials PRIMARY KEY (users_id)
);

-- 판매자 프로필·사업자 정보. users와 1:1, 관리자 승인 후 판매 활성화 (설계 DDL)
CREATE TABLE seller_schema.seller_profiles (
    id               uuid         NOT NULL DEFAULT gen_random_uuid(),
    user_id          uuid         NOT NULL,              -- ref: user_schema.users.id (1:1)
    status           varchar(10)  NOT NULL DEFAULT 'PENDING',  -- PENDING | APPROVED | REJECTED
    store_name       varchar(100) NOT NULL,
    business_number  varchar(20)  NOT NULL,              -- 암호화
    business_type    varchar(10)  NOT NULL DEFAULT 'INDIVIDUAL',
    rejection_reason text,
    approved_at      timestamptz,
    created_at       timestamptz  NOT NULL,
    updated_at       timestamptz  NOT NULL,
    CONSTRAINT pk_seller_profiles PRIMARY KEY (id)
);
CREATE UNIQUE INDEX ON seller_schema.seller_profiles (user_id);

-- 판매자 정산 계좌 이력. append-only — 교체 시 신규 INSERT, is_primary=true가 현행 (설계 DDL)
CREATE TABLE seller_schema.seller_bank_accounts (
    id             uuid         NOT NULL DEFAULT gen_random_uuid(),
    seller_id      uuid         NOT NULL,                -- ref: user_schema.users.id (1:N)
    bank_name      varchar(50)  NOT NULL,
    account_number varchar(100) NOT NULL,                -- 암호화
    account_holder varchar(50)  NOT NULL,                -- 암호화
    is_primary     boolean      NOT NULL DEFAULT false,  -- 판매자당 1개만 true
    is_verified    boolean      NOT NULL DEFAULT false,
    verified_at    timestamptz,
    created_at     timestamptz  NOT NULL,
    CONSTRAINT pk_seller_bank_accounts PRIMARY KEY (id)
);
CREATE INDEX ON seller_schema.seller_bank_accounts (seller_id);

-- 판매자별 배송비 정책. 상품 미연결 시 is_active=true 기본 정책 적용 (설계 DDL)
CREATE TABLE seller_schema.seller_shipping_policies (
    id             uuid        NOT NULL DEFAULT gen_random_uuid(),
    seller_id      uuid        NOT NULL,                 -- ref: user_schema.users.id
    condition_type varchar(25) NOT NULL,                 -- ALWAYS_FREE | FIXED | FREE_ABOVE_THRESHOLD
    base_fee       integer     NOT NULL DEFAULT 0,
    free_threshold integer,                              -- FREE_ABOVE_THRESHOLD일 때만
    is_active      boolean     NOT NULL DEFAULT true,
    created_at     timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL,
    CONSTRAINT pk_seller_shipping_policies PRIMARY KEY (id)
);
CREATE INDEX ON seller_schema.seller_shipping_policies (seller_id, is_active);

-- Transactional Outbox (seller 도메인 전용)
CREATE TABLE seller_schema.outbox_events (
    id             bigserial    NOT NULL,
    aggregate_type varchar(50)  NOT NULL,
    aggregate_id   uuid         NOT NULL,
    event_type     varchar(50)  NOT NULL,
    payload        jsonb        NOT NULL,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    processed_at   timestamptz,
    retry_count    integer      NOT NULL DEFAULT 0,
    last_error     text,
    CONSTRAINT pk_seller_outbox_events PRIMARY KEY (id)
);
CREATE INDEX ON seller_schema.outbox_events (processed_at, created_at);
CREATE INDEX ON seller_schema.outbox_events (aggregate_type, aggregate_id);
CREATE INDEX ON seller_schema.outbox_events (event_type, created_at);
CREATE INDEX ON seller_schema.outbox_events (retry_count, created_at);

-- 감사 로그 (seller 도메인 전용)
CREATE TABLE seller_schema.audit_logs (
    id          uuid         NOT NULL DEFAULT gen_random_uuid(),
    actor_id    uuid,
    actor_type  varchar(10)  NOT NULL,
    action      varchar(100) NOT NULL,
    target_id   uuid,
    target_type varchar(50),
    detail      jsonb,
    ip_address  varchar(45),
    created_at  timestamptz  NOT NULL,
    CONSTRAINT pk_seller_audit_logs PRIMARY KEY (id)
);
CREATE INDEX ON seller_schema.audit_logs (actor_id, actor_type, created_at);
CREATE INDEX ON seller_schema.audit_logs (action, created_at);
