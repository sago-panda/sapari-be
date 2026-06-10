-- notification 도메인 초기 스키마 (알림·디바이스 토큰·푸시 발송 이력·수신 설정)
-- 설계 DDL(sapari_postgres.sql) 기반, Postgres 타입 변환.
CREATE SCHEMA IF NOT EXISTS notification_schema;

-- 사용자 알림. 생성 시 활성 디바이스 토큰으로 푸시 발송 → push_notification_logs 기록
CREATE TABLE notification_schema.notifications (
    id             uuid         NOT NULL DEFAULT gen_random_uuid(),
    user_id        uuid         NOT NULL,                -- ref: user_schema.users.id
    type           varchar(35)  NOT NULL,
    title          varchar(255) NOT NULL,
    message        text         NOT NULL,
    reference_id   uuid,
    reference_type varchar(50),
    is_read        boolean      NOT NULL DEFAULT false,
    sent_at        timestamptz,
    created_at     timestamptz  NOT NULL,
    CONSTRAINT pk_notifications PRIMARY KEY (id)
);
CREATE INDEX ON notification_schema.notifications (user_id, is_read, created_at);

-- 사용자 디바이스별 푸시 토큰. 복수 디바이스 허용. INVALID_TOKEN 수신 시 is_active=false
CREATE TABLE notification_schema.user_device_tokens (
    id           uuid         NOT NULL DEFAULT gen_random_uuid(),
    user_id      uuid         NOT NULL,                  -- ref: user_schema.users.id
    token        varchar(255) NOT NULL,
    platform     varchar(10)  NOT NULL,
    device_name  varchar(100),
    is_active    boolean      NOT NULL DEFAULT true,
    last_used_at timestamptz,
    created_at   timestamptz  NOT NULL,
    updated_at   timestamptz  NOT NULL,
    CONSTRAINT pk_user_device_tokens PRIMARY KEY (id)
);
CREATE INDEX ON notification_schema.user_device_tokens (user_id, is_active);
CREATE UNIQUE INDEX ON notification_schema.user_device_tokens (token);

-- 알림 1건당 디바이스별 푸시 발송 이력
CREATE TABLE notification_schema.push_notification_logs (
    id              uuid         NOT NULL DEFAULT gen_random_uuid(),
    notification_id uuid         NOT NULL,
    device_token_id uuid         NOT NULL,
    status          varchar(15)  NOT NULL DEFAULT 'PENDING',
    failure_reason  varchar(255),
    sent_at         timestamptz,
    created_at      timestamptz  NOT NULL,
    CONSTRAINT pk_push_notification_logs PRIMARY KEY (id)
);
CREATE INDEX ON notification_schema.push_notification_logs (notification_id);
CREATE INDEX ON notification_schema.push_notification_logs (device_token_id, created_at);
CREATE INDEX ON notification_schema.push_notification_logs (status, created_at);

-- 사용자별 알림 수신 설정. users와 1:1
CREATE TABLE notification_schema.notification_settings (
    id                    uuid        NOT NULL DEFAULT gen_random_uuid(),
    user_id               uuid        NOT NULL,          -- ref: user_schema.users.id (1:1)
    restock_enabled       boolean     NOT NULL DEFAULT true,
    coupon_expiry_enabled boolean     NOT NULL DEFAULT true,
    grade_change_enabled  boolean     NOT NULL DEFAULT true,
    marketing_enabled     boolean     NOT NULL DEFAULT false,
    created_at            timestamptz NOT NULL,
    updated_at            timestamptz NOT NULL,
    CONSTRAINT pk_notification_settings PRIMARY KEY (id)
);
CREATE UNIQUE INDEX ON notification_schema.notification_settings (user_id);

-- Transactional Outbox (notification 도메인 전용)
CREATE TABLE notification_schema.outbox_events (
    id             bigserial    NOT NULL,
    aggregate_type varchar(50)  NOT NULL,
    aggregate_id   uuid         NOT NULL,
    event_type     varchar(50)  NOT NULL,
    payload        jsonb        NOT NULL,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    processed_at   timestamptz,
    retry_count    integer      NOT NULL DEFAULT 0,
    last_error     text,
    CONSTRAINT pk_notification_outbox_events PRIMARY KEY (id)
);
CREATE INDEX ON notification_schema.outbox_events (processed_at, created_at);
CREATE INDEX ON notification_schema.outbox_events (aggregate_type, aggregate_id);
CREATE INDEX ON notification_schema.outbox_events (event_type, created_at);
CREATE INDEX ON notification_schema.outbox_events (retry_count, created_at);

-- 감사 로그 (notification 도메인 전용)
CREATE TABLE notification_schema.audit_logs (
    id          uuid         NOT NULL DEFAULT gen_random_uuid(),
    actor_id    uuid,
    actor_type  varchar(10)  NOT NULL,
    action      varchar(100) NOT NULL,
    target_id   uuid,
    target_type varchar(50),
    detail      jsonb,
    ip_address  varchar(45),
    created_at  timestamptz  NOT NULL,
    CONSTRAINT pk_notification_audit_logs PRIMARY KEY (id)
);
CREATE INDEX ON notification_schema.audit_logs (actor_id, actor_type, created_at);
CREATE INDEX ON notification_schema.audit_logs (action, created_at);
