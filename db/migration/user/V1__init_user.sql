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
    profile_image_url       varchar(500),
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

-- 탈퇴회원 법정 보존용 최소 식별정보.
-- 주문/결제/환불 등 법정 보존 거래기록은 각 도메인 테이블에 그대로 보관하고,
-- 이 테이블은 users hard delete 이후에도 거래기록의 buyer_id/user_id와 연결할 최소 힌트만 보관한다.
-- 원문 개인정보, 해시, 복호화 가능한 암호화 식별정보는 저장하지 않고 마스킹 값만 저장한다.
CREATE TABLE user_schema.withdrawn_user_retentions (
    id                  uuid         NOT NULL DEFAULT gen_random_uuid(),
    original_user_id    uuid         NOT NULL,              -- 탈퇴 전 users.id 값. soft reference이며 FK 아님
    name_masked         varchar(20),                        -- 예: 홍길동 -> 홍*동
    email_masked        varchar(255),                       -- 예: test@example.com -> te***@example.com
    phone_number_masked varchar(20),                        -- 예: 01012345678 -> 010****5678
    retention_until     timestamptz  NOT NULL,              -- 법정 보존 만료 시각. 탈퇴 요청 시각 + 5년
    created_at          timestamptz  NOT NULL,
    purged_at           timestamptz,                         -- 파기 완료 시각. null이면 아직 보존 중
    CONSTRAINT pk_withdrawn_user_retentions PRIMARY KEY (id)
);
CREATE UNIQUE INDEX uk_withdrawn_user_retentions_original_user_id
    ON user_schema.withdrawn_user_retentions (original_user_id);
CREATE INDEX idx_withdrawn_user_retentions_retention_until
    ON user_schema.withdrawn_user_retentions (retention_until)
    WHERE purged_at IS NULL;

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
