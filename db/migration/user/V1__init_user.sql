-- user 도메인 초기 스키마
-- 출처: UserEntity (+ BaseUuidEntity/BaseTimeEntity). ddl-auto=validate와 일치해야 함.
CREATE SCHEMA IF NOT EXISTS user_schema;

CREATE TABLE user_schema.users (
    id                      uuid         NOT NULL,
    created_at              timestamptz  NOT NULL,
    updated_at              timestamptz  NOT NULL,
    role                    varchar(10)  NOT NULL,
    status                  varchar(15)  NOT NULL,
    nickname                varchar(10)  NOT NULL,
    nickname_changed_at     timestamptz  NOT NULL,
    name                    varchar(20),
    birth_date              date,
    gender                  varchar(10),
    phone_number            varchar(11)  NOT NULL,
    profile_image_key       varchar(500),
    email                   varchar(255) NOT NULL,
    grade                   varchar(30)  NOT NULL,
    point_balance           integer      NOT NULL,
    marketing_agreed        boolean      NOT NULL,
    suspended_until         timestamptz,
    suspension_reason       text,
    deleted_at              timestamptz,
    provider                varchar(255),
    provider_id             varchar(255),
    provider_email          varchar(255),
    provider_created_at     timestamptz,
    CONSTRAINT pk_users              PRIMARY KEY (id),
    CONSTRAINT uk_users_nickname     UNIQUE (nickname),
    CONSTRAINT uk_users_phone_number UNIQUE (phone_number),
    CONSTRAINT uk_users_email        UNIQUE (email)
);
CREATE INDEX ON user_schema.users (status, role);
CREATE INDEX idx_users_withdrawing_deleted_at
    ON user_schema.users (deleted_at)
    WHERE status = 'WITHDRAWING';

-- 구매자 전용 소셜 로그인 연동. users와 N:1 — 제공자별 복수 연동 가능 (설계 DDL)
-- ⚠️ 현 코드(UserEntity)는 provider 컬럼을 users에 내장 — 코드가 설계를 따라가면 이 테이블로 이관
CREATE TABLE user_schema.social_accounts (
    id             uuid         NOT NULL DEFAULT gen_random_uuid(),
    user_id        uuid         NOT NULL,
    provider       varchar(10)  NOT NULL,                -- NAVER | KAKAO | GOOGLE
    provider_id    varchar(255) NOT NULL,
    provider_email varchar(255),
    created_at     timestamptz  NOT NULL,
    CONSTRAINT pk_social_accounts PRIMARY KEY (id)
);
CREATE UNIQUE INDEX ON user_schema.social_accounts (provider, provider_id);

-- 관리자 프로필. users(role=ADMIN)·local_credentials와 1:1 (설계 DDL)
CREATE TABLE user_schema.admin_profiles (
    user_id          uuid        NOT NULL,               -- PK이자 ref: users.id (1:1)
    name             varchar(50) NOT NULL,
    is_super         boolean     NOT NULL DEFAULT false,
    password_changed boolean     NOT NULL DEFAULT false,
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    CONSTRAINT pk_admin_profiles PRIMARY KEY (user_id)
);

-- Transactional Outbox (user 도메인 전용)
CREATE TABLE user_schema.outbox_events (
    id             bigserial    NOT NULL,
    aggregate_type varchar(50)  NOT NULL,
    aggregate_id   uuid         NOT NULL,
    event_type     varchar(50)  NOT NULL,
    payload        jsonb        NOT NULL,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    processed_at   timestamptz,
    retry_count    integer      NOT NULL DEFAULT 0,
    last_error     text,
    CONSTRAINT pk_user_outbox_events PRIMARY KEY (id)
);
CREATE INDEX ON user_schema.outbox_events (processed_at, created_at);
CREATE INDEX ON user_schema.outbox_events (aggregate_type, aggregate_id);
CREATE INDEX ON user_schema.outbox_events (event_type, created_at);
CREATE INDEX ON user_schema.outbox_events (retry_count, created_at);

-- 감사 로그 (user 도메인 전용)
CREATE TABLE user_schema.audit_logs (
    id          uuid         NOT NULL DEFAULT gen_random_uuid(),
    actor_id    uuid,
    actor_type  varchar(10)  NOT NULL,
    action      varchar(100) NOT NULL,
    target_id   uuid,
    target_type varchar(50),
    detail      jsonb,
    ip_address  varchar(45),
    created_at  timestamptz  NOT NULL,
    CONSTRAINT pk_user_audit_logs PRIMARY KEY (id)
);
CREATE INDEX ON user_schema.audit_logs (actor_id, actor_type, created_at);
CREATE INDEX ON user_schema.audit_logs (action, created_at);
