-- order 도메인 초기 스키마 (장바구니·주문·결제·배송·교환/환불)
-- 설계 DDL(sapari_postgres.sql) 기반, Postgres 타입 변환.
-- payments는 order_groups와 1:1·동일 결제 트랜잭션이라 order 도메인에 포함.
CREATE SCHEMA IF NOT EXISTS order_schema;

-- 장바구니. option_combination_id NULL 여부로 partial unique 2개 분리 (PostgreSQL NULL≠NULL)
CREATE TABLE order_schema.cart_items (
    id                    uuid        NOT NULL DEFAULT gen_random_uuid(),
    user_id               uuid        NOT NULL,          -- ref: user_schema.users.id
    product_id            uuid        NOT NULL,          -- ref: product_schema.products.id
    option_combination_id uuid,                          -- ref: product_schema.product_option_combinations.id
    quantity              integer     NOT NULL DEFAULT 1,
    created_at            timestamptz NOT NULL,
    updated_at            timestamptz NOT NULL,
    CONSTRAINT pk_cart_items PRIMARY KEY (id)
);
CREATE UNIQUE INDEX ON order_schema.cart_items (user_id, product_id, option_combination_id)
    WHERE option_combination_id IS NOT NULL;
CREATE UNIQUE INDEX ON order_schema.cart_items (user_id, product_id)
    WHERE option_combination_id IS NULL;
CREATE INDEX ON order_schema.cart_items (user_id);

-- 구매자 결제 단위. 복수 판매자 상품을 하나의 결제로 묶음. payments와 1:1
CREATE TABLE order_schema.order_groups (
    id                     uuid         NOT NULL DEFAULT gen_random_uuid(),
    order_number           varchar(30)  NOT NULL,        -- 예: ORD-20260504-001234
    buyer_id               uuid         NOT NULL,        -- ref: user_schema.users.id
    total_amount           integer      NOT NULL,
    original_amount        integer      NOT NULL,
    shipping_fee           integer      NOT NULL DEFAULT 0,
    discount_amount        integer      NOT NULL DEFAULT 0,
    coupon_discount_amount integer      NOT NULL DEFAULT 0,
    point_used_amount      integer      NOT NULL DEFAULT 0,
    recipient_name         varchar(50)  NOT NULL,        -- 암호화
    phone                  varchar(20)  NOT NULL,        -- 암호화
    address                varchar(255) NOT NULL,        -- 암호화
    address_detail         varchar(100),                 -- 암호화
    postal_code            varchar(10)  NOT NULL,
    delivery_request       varchar(200),
    created_at             timestamptz  NOT NULL,
    updated_at             timestamptz  NOT NULL,
    CONSTRAINT pk_order_groups PRIMARY KEY (id)
);
CREATE UNIQUE INDEX ON order_schema.order_groups (order_number);
CREATE INDEX ON order_schema.order_groups (buyer_id, created_at);

-- 판매자별 이행 단위. order_groups 내 판매자당 1개. 배송비 산출 근거 스냅샷 포함
CREATE TABLE order_schema.orders (
    id                                uuid        NOT NULL DEFAULT gen_random_uuid(),
    order_group_id                    uuid        NOT NULL,
    seller_id                         uuid        NOT NULL,   -- ref: user_schema.users.id
    original_amount                   integer     NOT NULL,
    discount_amount                   integer     NOT NULL DEFAULT 0,
    coupon_discount_amount            integer     NOT NULL DEFAULT 0,
    shipping_fee                      integer     NOT NULL DEFAULT 0,
    shipping_condition_type_snapshot  varchar(25),
    shipping_base_fee_snapshot        integer,
    shipping_free_threshold_snapshot  integer,
    policy_applicable_total           integer,
    created_at                        timestamptz NOT NULL,
    updated_at                        timestamptz NOT NULL,
    CONSTRAINT pk_orders PRIMARY KEY (id)
);
CREATE INDEX ON order_schema.orders (order_group_id);
CREATE INDEX ON order_schema.orders (seller_id, created_at);

-- 주문 상품 라인. 상품명·단가·옵션·할인·쿠폰 스냅샷 포함
CREATE TABLE order_schema.order_items (
    id                               uuid         NOT NULL DEFAULT gen_random_uuid(),
    order_id                         uuid         NOT NULL,
    seller_id                        uuid         NOT NULL,  -- orders.seller_id 비정규화
    product_id                       uuid         NOT NULL,  -- ref: product_schema.products.id
    option_combination_id            uuid,                   -- ref: product_schema.product_option_combinations.id
    product_name_snapshot            varchar(255) NOT NULL,
    unit_price_snapshot              integer      NOT NULL,
    discount_amount_snapshot         integer      NOT NULL DEFAULT 0,
    applied_discount_policy_id       uuid,                   -- ref: product_schema.discount_policies.id
    user_coupon_id                   uuid,                   -- ref: promotion_schema.user_coupons.id
    coupon_discount_amount_snapshot  integer      NOT NULL DEFAULT 0,
    option_combination_snapshot      varchar(500),
    selected_option_values           jsonb,                  -- COMPONENT/HYBRID 전용
    additional_shipping_fee_snapshot integer      NOT NULL DEFAULT 0,
    quantity                         integer      NOT NULL,
    status                           varchar(25)  NOT NULL DEFAULT 'PENDING_PAYMENT',
    cancel_reason                    text,
    purchase_confirmed_at            timestamptz,
    auto_confirmed                   boolean      NOT NULL DEFAULT false,
    created_at                       timestamptz  NOT NULL,
    updated_at                       timestamptz  NOT NULL,
    CONSTRAINT pk_order_items PRIMARY KEY (id)
);
CREATE INDEX ON order_schema.order_items (order_id);
CREATE INDEX ON order_schema.order_items (seller_id, status, created_at);
CREATE INDEX ON order_schema.order_items (status, purchase_confirmed_at);
CREATE UNIQUE INDEX ON order_schema.order_items (user_coupon_id);

-- 주문 상품 상태 변경 이력
CREATE TABLE order_schema.order_item_status_histories (
    id            uuid        NOT NULL DEFAULT gen_random_uuid(),
    order_item_id uuid        NOT NULL,
    status        varchar(25) NOT NULL,
    changed_by    varchar(10) NOT NULL,
    note          text,
    created_at    timestamptz NOT NULL,
    CONSTRAINT pk_order_item_status_histories PRIMARY KEY (id)
);
CREATE INDEX ON order_schema.order_item_status_histories (order_item_id, created_at);

-- 결제 건. order_groups와 1:1. PG 거래·결제 수단·부분 환불 상태 관리
CREATE TABLE order_schema.payments (
    id                uuid         NOT NULL DEFAULT gen_random_uuid(),
    order_group_id    uuid         NOT NULL,
    pg_transaction_id varchar(255),
    pg_provider       varchar(30)  NOT NULL,
    payment_method    varchar(30)  NOT NULL,
    amount            integer      NOT NULL,
    status            varchar(20)  NOT NULL DEFAULT 'PENDING',
    paid_at           timestamptz,
    failed_at         timestamptz,
    cancelled_at      timestamptz,
    refunded_at       timestamptz,
    created_at        timestamptz  NOT NULL,
    updated_at        timestamptz  NOT NULL,
    CONSTRAINT pk_payments PRIMARY KEY (id)
);
CREATE UNIQUE INDEX ON order_schema.payments (order_group_id);
CREATE INDEX ON order_schema.payments (pg_transaction_id);

-- PG 환불 실행 단위. refund_requests N:1 — 재시도 시 복수, 성공/실패 이력 보존
CREATE TABLE order_schema.payment_refunds (
    id                       uuid         NOT NULL DEFAULT gen_random_uuid(),
    payment_id               uuid         NOT NULL,
    refund_request_id        uuid         NOT NULL,
    pg_refund_transaction_id varchar(255),
    amount                   integer      NOT NULL,
    status                   varchar(15)  NOT NULL DEFAULT 'PENDING',
    failed_reason            text,
    refunded_at              timestamptz,
    created_at               timestamptz  NOT NULL,
    updated_at               timestamptz  NOT NULL,
    CONSTRAINT pk_payment_refunds PRIMARY KEY (id)
);
CREATE INDEX ON order_schema.payment_refunds (payment_id);
CREATE INDEX ON order_schema.payment_refunds (refund_request_id);
CREATE INDEX ON order_schema.payment_refunds (pg_refund_transaction_id);

-- 배송 정보. order_items 1:N — delivery_type으로 최초/교환 재배송 구분
CREATE TABLE order_schema.deliveries (
    id                    uuid         NOT NULL DEFAULT gen_random_uuid(),
    order_item_id         uuid         NOT NULL,
    delivery_type         varchar(15)  NOT NULL DEFAULT 'INITIAL',
    courier_name          varchar(100) NOT NULL,
    carrier_code          varchar(20)  NOT NULL,
    tracking_number       varchar(100) NOT NULL,
    status                varchar(30)  NOT NULL DEFAULT 'PREPARING',
    shipped_at            timestamptz,
    estimated_delivery_at timestamptz,
    delivered_at          timestamptz,
    created_at            timestamptz  NOT NULL,
    CONSTRAINT pk_deliveries PRIMARY KEY (id)
);
CREATE INDEX ON order_schema.deliveries (order_item_id, created_at);
CREATE INDEX ON order_schema.deliveries (status);

-- 환불 요청. fault_party로 배송비 부담 기준 결정. 판매자 우선 승인 + 관리자 에스컬레이션
CREATE TABLE order_schema.refund_requests (
    id                         uuid         NOT NULL DEFAULT gen_random_uuid(),
    order_item_id              uuid         NOT NULL,
    user_id                    uuid         NOT NULL,    -- ref: user_schema.users.id
    request_reason_type        varchar(30)  NOT NULL,
    request_reason_detail      text,
    fault_party                varchar(10)  NOT NULL,
    refund_type                varchar(20)  NOT NULL,
    refund_quantity            integer      NOT NULL DEFAULT 1,
    refund_amount              integer      NOT NULL,
    shipping_fee_refund_amount integer      NOT NULL DEFAULT 0,
    payment_refund_method      varchar(30)  NOT NULL,
    refund_bank                varchar(50),
    refund_account_number      varchar(100),             -- 암호화
    refund_account_holder      varchar(50),              -- 암호화
    pickup_required            boolean      NOT NULL DEFAULT false,
    pickup_address             varchar(255),             -- 암호화
    pickup_address_detail      varchar(100),             -- 암호화
    pickup_postal_code         varchar(10),
    status                     varchar(30)  NOT NULL DEFAULT 'REQUESTED',
    rejected_reason            text,
    approved_by_type           varchar(10),
    approved_by_id             uuid,
    escalated_to_admin_at      timestamptz,
    approved_at                timestamptz,
    refunded_at                timestamptz,
    cancelled_at               timestamptz,
    created_at                 timestamptz  NOT NULL,
    updated_at                 timestamptz  NOT NULL,
    CONSTRAINT pk_refund_requests PRIMARY KEY (id)
);
CREATE INDEX ON order_schema.refund_requests (order_item_id);
CREATE INDEX ON order_schema.refund_requests (user_id, created_at);
CREATE INDEX ON order_schema.refund_requests (status, created_at);
CREATE INDEX ON order_schema.refund_requests (approved_by_type, approved_by_id);
CREATE INDEX ON order_schema.refund_requests (escalated_to_admin_at);

-- 교환 요청. 옵션 변경·차액 결제/환불, 회수·재배송 주소 관리
CREATE TABLE order_schema.exchange_requests (
    id                         uuid         NOT NULL DEFAULT gen_random_uuid(),
    order_item_id              uuid         NOT NULL,
    user_id                    uuid         NOT NULL,    -- ref: user_schema.users.id
    from_option_combination_id uuid,
    to_option_combination_id   uuid,
    request_reason_type        varchar(30)  NOT NULL,
    request_reason_detail      text,
    fault_party                varchar(10)  NOT NULL,
    additional_payment_amount  integer      NOT NULL DEFAULT 0,
    refund_amount              integer      NOT NULL DEFAULT 0,
    pickup_required            boolean      NOT NULL DEFAULT true,
    pickup_address             varchar(255),             -- 암호화
    pickup_address_detail      varchar(100),             -- 암호화
    pickup_postal_code         varchar(10),
    re_delivery_address        varchar(255) NOT NULL,    -- 암호화
    re_delivery_address_detail varchar(100),             -- 암호화
    re_delivery_postal_code    varchar(10)  NOT NULL,
    status                     varchar(30)  NOT NULL DEFAULT 'REQUESTED',
    rejected_reason            text,
    approved_by_type           varchar(10),
    approved_by_id             uuid,
    escalated_to_admin_at      timestamptz,
    approved_at                timestamptz,
    completed_at               timestamptz,
    cancelled_at               timestamptz,
    created_at                 timestamptz  NOT NULL,
    updated_at                 timestamptz  NOT NULL,
    CONSTRAINT pk_exchange_requests PRIMARY KEY (id)
);
CREATE INDEX ON order_schema.exchange_requests (order_item_id);
CREATE INDEX ON order_schema.exchange_requests (user_id, created_at);
CREATE INDEX ON order_schema.exchange_requests (status, created_at);
CREATE INDEX ON order_schema.exchange_requests (from_option_combination_id);
CREATE INDEX ON order_schema.exchange_requests (to_option_combination_id);
CREATE INDEX ON order_schema.exchange_requests (approved_by_type, approved_by_id);
CREATE INDEX ON order_schema.exchange_requests (escalated_to_admin_at);

-- Transactional Outbox (order 도메인 전용 — 주문/결제 이벤트)
CREATE TABLE order_schema.outbox_events (
    id             bigserial    NOT NULL,
    aggregate_type varchar(50)  NOT NULL,
    aggregate_id   uuid         NOT NULL,
    event_type     varchar(50)  NOT NULL,
    payload        jsonb        NOT NULL,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    processed_at   timestamptz,
    retry_count    integer      NOT NULL DEFAULT 0,
    last_error     text,
    CONSTRAINT pk_order_outbox_events PRIMARY KEY (id)
);
CREATE INDEX ON order_schema.outbox_events (processed_at, created_at);
CREATE INDEX ON order_schema.outbox_events (aggregate_type, aggregate_id);
CREATE INDEX ON order_schema.outbox_events (event_type, created_at);
CREATE INDEX ON order_schema.outbox_events (retry_count, created_at);

-- 감사 로그 (order 도메인 전용)
CREATE TABLE order_schema.audit_logs (
    id          uuid         NOT NULL DEFAULT gen_random_uuid(),
    actor_id    uuid,
    actor_type  varchar(10)  NOT NULL,
    action      varchar(100) NOT NULL,
    target_id   uuid,
    target_type varchar(50),
    detail      jsonb,
    ip_address  varchar(45),
    created_at  timestamptz  NOT NULL,
    CONSTRAINT pk_order_audit_logs PRIMARY KEY (id)
);
CREATE INDEX ON order_schema.audit_logs (actor_id, actor_type, created_at);
CREATE INDEX ON order_schema.audit_logs (action, created_at);
