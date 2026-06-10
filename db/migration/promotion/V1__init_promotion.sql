-- promotion 도메인 초기 스키마 (쿠폰·포인트·운영 콘텐츠)
-- 설계 DDL(sapari_postgres.sql) 기반, Postgres 타입 변환.
-- banners/notices는 자연 도메인이 없어 임시 수용(추후 content/admin 모듈 신설 시 이관 후보).
CREATE SCHEMA IF NOT EXISTS promotion_schema;

-- 쿠폰 템플릿. 발급 주체(issuer_type)·적용 범위(scope_type)·할인 방식·수량 제한
CREATE TABLE promotion_schema.coupon_templates (
    id                  uuid         NOT NULL DEFAULT gen_random_uuid(),
    issuer_type         varchar(10)  NOT NULL,           -- SELLER | ADMIN
    issuer_id           uuid,                            -- ref: user_schema.users.id
    scope_type          varchar(10)  NOT NULL DEFAULT 'ALL',  -- ALL | CATEGORY | PRODUCT
    name                varchar(100) NOT NULL,
    discount_type       varchar(10)  NOT NULL DEFAULT 'RATE',
    discount_rate       smallint,
    discount_amount     integer,
    max_discount_amount integer,
    min_order_amount    integer      NOT NULL DEFAULT 0,
    total_quantity      integer,
    issued_count        integer      NOT NULL DEFAULT 0,
    per_user_limit      smallint     NOT NULL DEFAULT 1,
    started_at          timestamptz  NOT NULL,
    expired_at          timestamptz  NOT NULL,
    is_active           boolean      NOT NULL DEFAULT true,
    created_at          timestamptz  NOT NULL,
    updated_at          timestamptz  NOT NULL,
    CONSTRAINT pk_coupon_templates PRIMARY KEY (id)
);
CREATE INDEX ON promotion_schema.coupon_templates (expired_at, is_active);
CREATE INDEX ON promotion_schema.coupon_templates (scope_type, is_active, expired_at);

-- scope_type=CATEGORY 쿠폰의 적용 카테고리 (ltree 서브트리 매칭은 앱/쿼리에서)
CREATE TABLE promotion_schema.coupon_applicable_categories (
    id                 uuid        NOT NULL DEFAULT gen_random_uuid(),
    coupon_template_id uuid        NOT NULL,
    category_id        uuid        NOT NULL,             -- ref: product_schema.categories.id
    created_at         timestamptz NOT NULL,
    CONSTRAINT pk_coupon_applicable_categories PRIMARY KEY (id)
);
CREATE UNIQUE INDEX ON promotion_schema.coupon_applicable_categories (coupon_template_id, category_id);
CREATE INDEX ON promotion_schema.coupon_applicable_categories (category_id);

-- scope_type=PRODUCT 쿠폰의 적용 상품 목록
CREATE TABLE promotion_schema.coupon_applicable_products (
    id                 uuid        NOT NULL DEFAULT gen_random_uuid(),
    coupon_template_id uuid        NOT NULL,
    product_id         uuid        NOT NULL,             -- ref: product_schema.products.id
    created_at         timestamptz NOT NULL,
    CONSTRAINT pk_coupon_applicable_products PRIMARY KEY (id)
);
CREATE UNIQUE INDEX ON promotion_schema.coupon_applicable_products (coupon_template_id, product_id);
CREATE INDEX ON promotion_schema.coupon_applicable_products (product_id);

-- 사용자 발급 쿠폰 인스턴스. 사용처는 order_items.user_coupon_id로 역참조
CREATE TABLE promotion_schema.user_coupons (
    id                 uuid        NOT NULL DEFAULT gen_random_uuid(),
    user_id            uuid        NOT NULL,             -- ref: user_schema.users.id
    coupon_template_id uuid        NOT NULL,
    used_at            timestamptz,
    expires_at         timestamptz,
    cancelled_at       timestamptz,
    created_at         timestamptz NOT NULL,
    CONSTRAINT pk_user_coupons PRIMARY KEY (id)
);
CREATE INDEX ON promotion_schema.user_coupons (user_id, used_at);
CREATE INDEX ON promotion_schema.user_coupons (coupon_template_id);

-- 포인트 변동 이력. 거래 후 잔액 스냅샷으로 복원 가능 (잔액 캐시는 user_schema.users.point_balance)
CREATE TABLE promotion_schema.point_transactions (
    id             uuid         NOT NULL DEFAULT gen_random_uuid(),
    user_id        uuid         NOT NULL,                -- ref: user_schema.users.id
    type           varchar(20)  NOT NULL,
    amount         integer      NOT NULL,
    balance_after  integer      NOT NULL,
    reference_id   uuid,
    reference_type varchar(50),
    note           varchar(255),
    created_at     timestamptz  NOT NULL,
    CONSTRAINT pk_point_transactions PRIMARY KEY (id)
);
CREATE INDEX ON promotion_schema.point_transactions (user_id, created_at);

-- 배너 (위치별 독립 관리, 관리자 등록)
CREATE TABLE promotion_schema.banners (
    id         uuid         NOT NULL DEFAULT gen_random_uuid(),
    position   varchar(20)  NOT NULL DEFAULT 'MAIN',
    image_key  varchar(500) NOT NULL,
    link_url   varchar(500),
    sort_order integer      NOT NULL DEFAULT 0,
    started_at timestamptz,
    ended_at   timestamptz,
    is_active  boolean      NOT NULL DEFAULT true,
    created_by uuid         NOT NULL,                    -- ref: user_schema.users.id (ADMIN)
    created_at timestamptz  NOT NULL,
    updated_at timestamptz  NOT NULL,
    CONSTRAINT pk_banners PRIMARY KEY (id)
);

-- 운영 공지사항. 대상 구분, 팝업·상단 고정 지원
CREATE TABLE promotion_schema.notices (
    id         uuid         NOT NULL DEFAULT gen_random_uuid(),
    admin_id   uuid         NOT NULL,                    -- ref: user_schema.users.id (ADMIN)
    target     varchar(10)  NOT NULL DEFAULT 'ALL',
    title      varchar(255) NOT NULL,
    content    text         NOT NULL,
    is_pinned  boolean      NOT NULL DEFAULT false,
    is_popup   boolean      NOT NULL DEFAULT false,
    is_visible boolean      NOT NULL DEFAULT true,
    deleted_at timestamptz,
    created_at timestamptz  NOT NULL,
    updated_at timestamptz  NOT NULL,
    CONSTRAINT pk_notices PRIMARY KEY (id)
);
CREATE INDEX ON promotion_schema.notices (target, is_visible, is_pinned, created_at);

-- Transactional Outbox (promotion 도메인 전용 — 쿠폰 발급/포인트 이벤트)
CREATE TABLE promotion_schema.outbox_events (
    id             bigserial    NOT NULL,
    aggregate_type varchar(50)  NOT NULL,
    aggregate_id   uuid         NOT NULL,
    event_type     varchar(50)  NOT NULL,
    payload        jsonb        NOT NULL,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    processed_at   timestamptz,
    retry_count    integer      NOT NULL DEFAULT 0,
    last_error     text,
    CONSTRAINT pk_promotion_outbox_events PRIMARY KEY (id)
);
CREATE INDEX ON promotion_schema.outbox_events (processed_at, created_at);
CREATE INDEX ON promotion_schema.outbox_events (aggregate_type, aggregate_id);
CREATE INDEX ON promotion_schema.outbox_events (event_type, created_at);
CREATE INDEX ON promotion_schema.outbox_events (retry_count, created_at);

-- 감사 로그 (promotion 도메인 전용)
CREATE TABLE promotion_schema.audit_logs (
    id          uuid         NOT NULL DEFAULT gen_random_uuid(),
    actor_id    uuid,
    actor_type  varchar(10)  NOT NULL,
    action      varchar(100) NOT NULL,
    target_id   uuid,
    target_type varchar(50),
    detail      jsonb,
    ip_address  varchar(45),
    created_at  timestamptz  NOT NULL,
    CONSTRAINT pk_promotion_audit_logs PRIMARY KEY (id)
);
CREATE INDEX ON promotion_schema.audit_logs (actor_id, actor_type, created_at);
CREATE INDEX ON promotion_schema.audit_logs (action, created_at);
