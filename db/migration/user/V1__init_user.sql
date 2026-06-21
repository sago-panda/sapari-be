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
    profile_image_key       varchar(200),
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

-- 회원가입 시점의 약관 버전을 증적 row가 참조하기 위한 불변 기준 데이터.
-- 이미 증적에 사용된 row는 삭제/수정하지 않고, 약관 변경은 새 version row를 추가한다.
-- active=true는 예약 약관이 아니라 현재 유효한 약관 1개를 뜻하며, content_url은 당시 전문 위치를 가리킨다.
CREATE TABLE user_schema.terms (
    id              uuid         NOT NULL DEFAULT gen_random_uuid(),
    type            varchar(30)  NOT NULL,
    version         varchar(30)  NOT NULL,
    title           varchar(100) NOT NULL,
    required        boolean      NOT NULL DEFAULT false, -- 표시/메타데이터용 필수 여부. 가입 검증은 TermsType.PRIVACY 정책으로 강제한다.
    content_url     varchar(500) NOT NULL,
    content_format  varchar(20)  NOT NULL,
    effective_from  timestamptz  NOT NULL,
    active          boolean      NOT NULL DEFAULT true,
    created_at      timestamptz  NOT NULL,
    updated_at      timestamptz  NOT NULL,
    CONSTRAINT pk_terms PRIMARY KEY (id)
);
CREATE UNIQUE INDEX uk_terms_type_version
    ON user_schema.terms (type, version);
-- type별 active=true 현재 유효 약관은 하나만 유지한다.
-- 가입 조회는 active=true와 effective_from <= 가입시각 조건을 함께 사용해 미래 약관 오등록을 방어한다.
CREATE UNIQUE INDEX uk_terms_type_active_true
    ON user_schema.terms (type)
    WHERE active = true;

-- 사용자가 가입 시점에 어떤 약관 버전에 동의/거부했는지 남기는 증적 테이블.
-- user_id와 terms_id는 FK를 두지 않는 soft reference다.
-- users hard delete와 증적 보존 정책이 충돌하지 않도록 정합성은 가입 서비스에서 보장한다.
-- MARKETING 같은 선택 약관은 agreed=false도 명시적 거부 증적으로 저장한다.
CREATE TABLE user_schema.user_terms_agreements (
    id          uuid        NOT NULL DEFAULT gen_random_uuid(),
    user_id     uuid        NOT NULL,
    terms_id    uuid        NOT NULL,
    agreed      boolean     NOT NULL,
    agreed_at   timestamptz NOT NULL,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    CONSTRAINT pk_user_terms_agreements PRIMARY KEY (id)
);
-- 회원가입 시점 증적이므로 같은 사용자/약관버전 중복 row를 막는다.
-- 이 unique index는 사용자별 조회(user_id prefix)와 (user_id, terms_id) 조회에도 사용되므로 별도 중복 인덱스를 두지 않는다.
CREATE UNIQUE INDEX uk_user_terms_agreements_user_terms
    ON user_schema.user_terms_agreements (user_id, terms_id);

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
