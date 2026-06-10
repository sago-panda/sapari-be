-- review 도메인 초기 스키마 (구매확정 후 리뷰 — product에서 분리: 포토리뷰 포인트 보상 정책 보유)
-- 설계 DDL(sapari_postgres.sql) 기반, Postgres 타입 변환.
CREATE SCHEMA IF NOT EXISTS review_schema;

-- 구매 확정 후 리뷰. order_items와 1:1. 별점·텍스트·사진, 포인트 지급 관리
CREATE TABLE review_schema.reviews (
    id             uuid        NOT NULL DEFAULT gen_random_uuid(),
    order_item_id  uuid        NOT NULL,                 -- ref: order_schema.order_items.id (1:1)
    user_id        uuid        NOT NULL,                 -- ref: user_schema.users.id
    product_id     uuid        NOT NULL,                 -- ref: product_schema.products.id
    rating         smallint    NOT NULL,                 -- 1~5
    content        text,                                 -- 최대 500자
    has_image      boolean     NOT NULL DEFAULT false,
    point_rewarded boolean     NOT NULL DEFAULT false,   -- 사진 첨부 포인트 지급 여부
    deleted_at     timestamptz,
    created_at     timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL,
    CONSTRAINT pk_reviews PRIMARY KEY (id)
);
CREATE UNIQUE INDEX ON review_schema.reviews (order_item_id);
CREATE INDEX ON review_schema.reviews (product_id, deleted_at, created_at);

-- 리뷰 첨부 이미지 (최대 5장). 사진 리뷰 포인트 지급 판별 기준
CREATE TABLE review_schema.review_images (
    id         uuid         NOT NULL DEFAULT gen_random_uuid(),
    review_id  uuid         NOT NULL,
    image_key  varchar(500) NOT NULL,
    sort_order smallint     NOT NULL,
    created_at timestamptz  NOT NULL,
    CONSTRAINT pk_review_images PRIMARY KEY (id)
);
CREATE INDEX ON review_schema.review_images (review_id, sort_order);

-- 판매자 리뷰 답글. reviews와 1:1
CREATE TABLE review_schema.review_replies (
    id         uuid        NOT NULL DEFAULT gen_random_uuid(),
    review_id  uuid        NOT NULL,
    seller_id  uuid        NOT NULL,                     -- ref: user_schema.users.id
    content    text        NOT NULL,                     -- 최대 500자
    deleted_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT pk_review_replies PRIMARY KEY (id)
);
CREATE UNIQUE INDEX ON review_schema.review_replies (review_id);

-- Transactional Outbox (review 도메인 전용 — 예: 포토리뷰 포인트 지급 이벤트 → promotion)
CREATE TABLE review_schema.outbox_events (
    id             bigserial    NOT NULL,
    aggregate_type varchar(50)  NOT NULL,
    aggregate_id   uuid         NOT NULL,
    event_type     varchar(50)  NOT NULL,
    payload        jsonb        NOT NULL,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    processed_at   timestamptz,
    retry_count    integer      NOT NULL DEFAULT 0,
    last_error     text,
    CONSTRAINT pk_review_outbox_events PRIMARY KEY (id)
);
CREATE INDEX ON review_schema.outbox_events (processed_at, created_at);
CREATE INDEX ON review_schema.outbox_events (aggregate_type, aggregate_id);
CREATE INDEX ON review_schema.outbox_events (event_type, created_at);
CREATE INDEX ON review_schema.outbox_events (retry_count, created_at);

-- 감사 로그 (review 도메인 전용)
CREATE TABLE review_schema.audit_logs (
    id          uuid         NOT NULL DEFAULT gen_random_uuid(),
    actor_id    uuid,
    actor_type  varchar(10)  NOT NULL,
    action      varchar(100) NOT NULL,
    target_id   uuid,
    target_type varchar(50),
    detail      jsonb,
    ip_address  varchar(45),
    created_at  timestamptz  NOT NULL,
    CONSTRAINT pk_review_audit_logs PRIMARY KEY (id)
);
CREATE INDEX ON review_schema.audit_logs (actor_id, actor_type, created_at);
CREATE INDEX ON review_schema.audit_logs (action, created_at);
