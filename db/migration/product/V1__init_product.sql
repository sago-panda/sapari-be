-- product 도메인 초기 스키마 (카탈로그·옵션·가격·문의·랭킹/검색 — review는 review_schema로 분리)
-- 설계 DDL(sapari_postgres.sql) 기반, Postgres 타입 변환.
-- 도메인 간 참조는 물리 FK 없이 uuid id 컬럼(프로젝트 규칙). 도메인 내부도 설계상 FK 미사용.
CREATE SCHEMA IF NOT EXISTS product_schema;

-- categories.path 용 ltree 확장 (DB당 1회. flyway_role에 권한 없으면 DBA가 사전 생성)
CREATE EXTENSION IF NOT EXISTS ltree;

-- ============================================================
-- 카탈로그
-- ============================================================

-- 상품 카테고리 트리 (ltree, depth 최대 4)
CREATE TABLE product_schema.categories (
    id         uuid         NOT NULL DEFAULT gen_random_uuid(),
    parent_id  uuid,                                     -- self ref (NULL=최상위)
    path       ltree        NOT NULL,
    name       varchar(100) NOT NULL,
    depth      smallint     NOT NULL,
    sort_order integer      NOT NULL DEFAULT 0,
    is_active  boolean      NOT NULL DEFAULT true,
    created_at timestamptz  NOT NULL,
    updated_at timestamptz  NOT NULL,
    CONSTRAINT pk_categories PRIMARY KEY (id)
);
CREATE INDEX ON product_schema.categories USING GIST (path);
CREATE INDEX ON product_schema.categories (parent_id, sort_order);
CREATE INDEX ON product_schema.categories (depth, is_active, sort_order);

-- 상품 원장. 정렬·필터 캐시 컬럼은 앱이 갱신 책임
CREATE TABLE product_schema.products (
    id                      uuid         NOT NULL DEFAULT gen_random_uuid(),
    seller_id               uuid         NOT NULL,       -- ref: user_schema.users.id
    category_id             uuid         NOT NULL,
    name                    varchar(255) NOT NULL,
    description             text,
    base_price              integer      NOT NULL,
    status                  varchar(20)  NOT NULL DEFAULT 'PENDING_REVIEW',
    shipping_policy_id      uuid,                        -- ref: seller_schema.seller_shipping_policies.id
    additional_shipping_fee integer      NOT NULL DEFAULT 0,
    rejection_reason        text,
    deleted_at              timestamptz,
    created_at              timestamptz  NOT NULL,
    updated_at              timestamptz  NOT NULL,
    min_price               integer,
    has_stock               boolean      NOT NULL DEFAULT true,
    purchase_count          integer      NOT NULL DEFAULT 0,
    avg_rating              numeric(3,1),
    review_count            integer      NOT NULL DEFAULT 0,
    view_count              integer      NOT NULL DEFAULT 0,
    wishlist_count          integer      NOT NULL DEFAULT 0,
    option_model            varchar(20)  NOT NULL DEFAULT 'COMBINATION',
    stock_total             integer,
    search_indexed_at       timestamptz,
    CONSTRAINT pk_products PRIMARY KEY (id)
);
CREATE INDEX ON product_schema.products (seller_id, status, deleted_at);
CREATE INDEX ON product_schema.products (category_id, status, deleted_at);
CREATE INDEX ON product_schema.products (status, deleted_at, min_price);
CREATE INDEX ON product_schema.products (status, deleted_at, purchase_count);
CREATE INDEX ON product_schema.products (status, deleted_at, avg_rating);
CREATE INDEX ON product_schema.products (status, deleted_at, wishlist_count);
CREATE INDEX ON product_schema.products (status, deleted_at, view_count);
CREATE INDEX ON product_schema.products (status, created_at);
CREATE INDEX ON product_schema.products (shipping_policy_id);
CREATE INDEX ON product_schema.products (option_model);
CREATE INDEX ON product_schema.products (search_indexed_at);

-- 상품 태그. 상품당 최대 10개
CREATE TABLE product_schema.product_tags (
    id         uuid        NOT NULL DEFAULT gen_random_uuid(),
    product_id uuid        NOT NULL,
    name       varchar(20) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT pk_product_tags PRIMARY KEY (id)
);
CREATE INDEX ON product_schema.product_tags (product_id);
CREATE INDEX ON product_schema.product_tags (name);

-- 판매자가 묶은 관련 상품 그룹
CREATE TABLE product_schema.product_group_sets (
    id         uuid         NOT NULL DEFAULT gen_random_uuid(),
    seller_id  uuid         NOT NULL,
    group_name varchar(255),
    created_at timestamptz  NOT NULL,
    updated_at timestamptz  NOT NULL,
    CONSTRAINT pk_product_group_sets PRIMARY KEY (id)
);

-- 상품 그룹 구성 아이템
CREATE TABLE product_schema.product_group_items (
    id           uuid        NOT NULL DEFAULT gen_random_uuid(),
    group_set_id uuid        NOT NULL,
    product_id   uuid        NOT NULL,
    sort_order   integer     NOT NULL DEFAULT 0,
    created_at   timestamptz NOT NULL,
    CONSTRAINT pk_product_group_items PRIMARY KEY (id)
);
CREATE UNIQUE INDEX ON product_schema.product_group_items (group_set_id, product_id);
CREATE INDEX ON product_schema.product_group_items (product_id);

-- 상품 대표 이미지 (최대 5장)
CREATE TABLE product_schema.product_images (
    id         uuid         NOT NULL DEFAULT gen_random_uuid(),
    product_id uuid         NOT NULL,
    image_key  varchar(500) NOT NULL,
    sort_order smallint     NOT NULL,
    created_at timestamptz  NOT NULL,
    CONSTRAINT pk_product_images PRIMARY KEY (id)
);
CREATE INDEX ON product_schema.product_images (product_id, sort_order);

-- 상품 상세 설명 이미지 (최대 10장)
CREATE TABLE product_schema.product_detail_images (
    id         uuid         NOT NULL DEFAULT gen_random_uuid(),
    product_id uuid         NOT NULL,
    image_key  varchar(500) NOT NULL,
    sort_order smallint     NOT NULL,
    created_at timestamptz  NOT NULL,
    CONSTRAINT pk_product_detail_images PRIMARY KEY (id)
);

-- ============================================================
-- 옵션
-- ============================================================

-- 옵션 속성 그룹 템플릿. 관리자 공용 + 판매자 커스텀
CREATE TABLE product_schema.option_attribute_groups (
    id          uuid         NOT NULL DEFAULT gen_random_uuid(),
    name        varchar(50)  NOT NULL,
    is_system   boolean      NOT NULL DEFAULT true,
    seller_id   uuid,                                    -- 커스텀 생성 판매자 (시스템은 NULL)
    description varchar(255),
    created_at  timestamptz  NOT NULL,
    updated_at  timestamptz  NOT NULL,
    CONSTRAINT pk_option_attribute_groups PRIMARY KEY (id)
);
CREATE INDEX ON product_schema.option_attribute_groups (is_system);
CREATE INDEX ON product_schema.option_attribute_groups (seller_id);

-- 속성 그룹별 사전 정의 값 (드롭박스 선택지)
CREATE TABLE product_schema.option_attribute_group_presets (
    id                 uuid         NOT NULL DEFAULT gen_random_uuid(),
    attribute_group_id uuid         NOT NULL,
    value              varchar(100) NOT NULL,
    sort_order         integer      NOT NULL DEFAULT 0,
    created_at         timestamptz  NOT NULL,
    CONSTRAINT pk_option_attribute_group_presets PRIMARY KEY (id)
);
CREATE INDEX ON product_schema.option_attribute_group_presets (attribute_group_id, sort_order);

-- 카테고리별 권장 속성 그룹 매핑
CREATE TABLE product_schema.category_option_attribute_groups (
    id                 uuid        NOT NULL DEFAULT gen_random_uuid(),
    category_id        uuid        NOT NULL,
    attribute_group_id uuid        NOT NULL,
    sort_order         integer     NOT NULL DEFAULT 0,
    created_at         timestamptz NOT NULL,
    CONSTRAINT pk_category_option_attribute_groups PRIMARY KEY (id)
);
CREATE UNIQUE INDEX ON product_schema.category_option_attribute_groups (category_id, attribute_group_id);
CREATE INDEX ON product_schema.category_option_attribute_groups (category_id, sort_order);

-- 상품별 옵션 타입 (색상, 사이즈 등)
CREATE TABLE product_schema.product_option_types (
    id                 uuid        NOT NULL DEFAULT gen_random_uuid(),
    product_id         uuid        NOT NULL,
    attribute_group_id uuid,                             -- NULL=완전 커스텀
    name               varchar(50) NOT NULL,
    sort_order         smallint    NOT NULL,
    deleted_at         timestamptz,
    created_at         timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL,
    CONSTRAINT pk_product_option_types PRIMARY KEY (id)
);
CREATE INDEX ON product_schema.product_option_types (product_id, sort_order);
CREATE INDEX ON product_schema.product_option_types (attribute_group_id);
CREATE UNIQUE INDEX ON product_schema.product_option_types (product_id, name);

-- 옵션 타입별 선택 값 (빨강, M 등)
CREATE TABLE product_schema.product_option_values (
    id                  uuid         NOT NULL DEFAULT gen_random_uuid(),
    option_type_id      uuid         NOT NULL,
    attribute_preset_id uuid,                            -- NULL=직접 입력
    value               varchar(100) NOT NULL,
    metadata            jsonb,                           -- 예: {"hex":"#FF0000"}
    price_delta         integer      NOT NULL DEFAULT 0,
    sort_order          smallint     NOT NULL,
    deleted_at          timestamptz,
    created_at          timestamptz  NOT NULL,
    updated_at          timestamptz  NOT NULL,
    CONSTRAINT pk_product_option_values PRIMARY KEY (id)
);
CREATE INDEX ON product_schema.product_option_values (option_type_id, sort_order);
CREATE UNIQUE INDEX ON product_schema.product_option_values (option_type_id, value);

-- 옵션 조합별 재고·가격·SKU 관리 단위 (상품당 최대 500개)
CREATE TABLE product_schema.product_option_combinations (
    id                uuid         NOT NULL DEFAULT gen_random_uuid(),
    product_id        uuid         NOT NULL,
    sku               varchar(100),
    combination_key   varchar(500) NOT NULL,             -- 옵션값 ID 오름차순 조합 문자열
    original_price    integer,
    price             integer      NOT NULL,
    stock             integer      NOT NULL DEFAULT 0,
    is_available      boolean      NOT NULL DEFAULT true,
    search_indexed_at timestamptz,
    created_at        timestamptz  NOT NULL,
    updated_at        timestamptz  NOT NULL,
    CONSTRAINT pk_product_option_combinations PRIMARY KEY (id)
);
CREATE UNIQUE INDEX ON product_schema.product_option_combinations (product_id, combination_key);
CREATE INDEX ON product_schema.product_option_combinations (product_id, stock);
CREATE INDEX ON product_schema.product_option_combinations (product_id, is_available);
CREATE UNIQUE INDEX ON product_schema.product_option_combinations (product_id, sku);

-- 옵션 조합 ↔ 옵션 값 매핑
CREATE TABLE product_schema.product_option_combination_values (
    id                    uuid        NOT NULL DEFAULT gen_random_uuid(),
    option_combination_id uuid        NOT NULL,
    option_value_id       uuid        NOT NULL,
    created_at            timestamptz NOT NULL,
    CONSTRAINT pk_product_option_combination_values PRIMARY KEY (id)
);
CREATE UNIQUE INDEX ON product_schema.product_option_combination_values (option_combination_id, option_value_id);
CREATE INDEX ON product_schema.product_option_combination_values (option_value_id);

-- 옵션 값(주로 색상) 단위 이미지
CREATE TABLE product_schema.product_option_value_images (
    id              uuid         NOT NULL DEFAULT gen_random_uuid(),
    option_value_id uuid         NOT NULL,
    image_key       varchar(500) NOT NULL,
    sort_order      smallint     NOT NULL,
    created_at      timestamptz  NOT NULL,
    CONSTRAINT pk_product_option_value_images PRIMARY KEY (id)
);
CREATE INDEX ON product_schema.product_option_value_images (option_value_id, sort_order);

-- 조합 단위 이미지 (예외용 — 행 존재 시 value_images보다 우선)
CREATE TABLE product_schema.product_option_combination_images (
    id                    uuid         NOT NULL DEFAULT gen_random_uuid(),
    option_combination_id uuid         NOT NULL,
    image_key             varchar(500) NOT NULL,
    sort_order            smallint     NOT NULL,
    created_at            timestamptz  NOT NULL,
    CONSTRAINT pk_product_option_combination_images PRIMARY KEY (id)
);
CREATE INDEX ON product_schema.product_option_combination_images (option_combination_id, sort_order);

-- 옵션값 비양립 쌍 (Blacklist, COMPONENT 전환 대비)
CREATE TABLE product_schema.product_option_incompatibility (
    id                bigserial    NOT NULL,
    product_id        uuid         NOT NULL,
    option_value_id_a uuid         NOT NULL,
    option_value_id_b uuid         NOT NULL,
    reason            varchar(255),
    created_at        timestamptz  NOT NULL,
    CONSTRAINT pk_product_option_incompatibility PRIMARY KEY (id)
);
CREATE UNIQUE INDEX ON product_schema.product_option_incompatibility (product_id, option_value_id_a, option_value_id_b);
CREATE INDEX ON product_schema.product_option_incompatibility (product_id);
CREATE INDEX ON product_schema.product_option_incompatibility (option_value_id_a);
CREATE INDEX ON product_schema.product_option_incompatibility (option_value_id_b);

-- 옵션값 의존성 룰 (Dependency, COMPONENT 전환 대비)
CREATE TABLE product_schema.product_option_dependency (
    id                       bigserial    NOT NULL,
    product_id               uuid         NOT NULL,
    master_option_value_id   uuid         NOT NULL,
    dependent_option_type_id uuid         NOT NULL,
    allowed_value_ids        uuid[]       NOT NULL,
    rule_type                varchar(10)  NOT NULL DEFAULT 'ALLOW',
    note                     varchar(255),
    created_at               timestamptz  NOT NULL,
    updated_at               timestamptz  NOT NULL,
    CONSTRAINT pk_product_option_dependency PRIMARY KEY (id)
);
CREATE UNIQUE INDEX ON product_schema.product_option_dependency (product_id, master_option_value_id, dependent_option_type_id);
CREATE INDEX ON product_schema.product_option_dependency (product_id);
CREATE INDEX ON product_schema.product_option_dependency (master_option_value_id);
CREATE INDEX ON product_schema.product_option_dependency (dependent_option_type_id);

-- ============================================================
-- 가격 / 할인 (판매자·관리자 모두 생성 — 주체는 created_by의 role로 구분)
-- ============================================================

-- 기간·상시 할인 정책. 중복 시 할인액 최대 정책 단독 적용
CREATE TABLE product_schema.discount_policies (
    id             uuid         NOT NULL DEFAULT gen_random_uuid(),
    name           varchar(100) NOT NULL,
    description    text,
    discount_type  varchar(15)  NOT NULL,                -- RATE | FIXED_AMOUNT
    discount_value integer      NOT NULL,
    started_at     timestamptz,                          -- NULL=즉시
    ended_at       timestamptz,                          -- NULL=상시
    is_active      boolean      NOT NULL DEFAULT true,
    created_by     uuid         NOT NULL,                -- ref: users.id (SELLER 또는 ADMIN)
    created_at     timestamptz  NOT NULL,
    updated_at     timestamptz  NOT NULL,
    CONSTRAINT pk_discount_policies PRIMARY KEY (id)
);
CREATE INDEX ON product_schema.discount_policies (is_active, started_at, ended_at);
CREATE INDEX ON product_schema.discount_policies (is_active, created_at);

-- 할인 정책 ↔ 상품 매핑 (product-level)
CREATE TABLE product_schema.discount_policy_products (
    id                 uuid        NOT NULL DEFAULT gen_random_uuid(),
    discount_policy_id uuid        NOT NULL,
    product_id         uuid        NOT NULL,
    created_at         timestamptz NOT NULL,
    CONSTRAINT pk_discount_policy_products PRIMARY KEY (id)
);
CREATE UNIQUE INDEX ON product_schema.discount_policy_products (discount_policy_id, product_id);
CREATE INDEX ON product_schema.discount_policy_products (product_id);

-- 할인 정책 ↔ 조합 매핑 (combination-level, product-level보다 우선)
CREATE TABLE product_schema.discount_policy_combinations (
    id                 uuid        NOT NULL DEFAULT gen_random_uuid(),
    discount_policy_id uuid        NOT NULL,
    combination_id     uuid        NOT NULL,
    created_at         timestamptz NOT NULL,
    CONSTRAINT pk_discount_policy_combinations PRIMARY KEY (id)
);
CREATE UNIQUE INDEX ON product_schema.discount_policy_combinations (discount_policy_id, combination_id);
CREATE INDEX ON product_schema.discount_policy_combinations (combination_id);

-- 조합당 현재 최적 할인 1개 (파생 캐시. 행 없음=할인 없음)
CREATE TABLE product_schema.combination_winning_discounts (
    combination_id  uuid        NOT NULL,                -- 1:1 product_option_combinations.id
    policy_id       uuid        NOT NULL,
    discount_amount integer     NOT NULL,
    final_price     integer     NOT NULL,
    resolved_at     timestamptz NOT NULL,
    CONSTRAINT pk_combination_winning_discounts PRIMARY KEY (combination_id)
);

-- ============================================================
-- 상품 문의 (구매 전 Q&A)
-- ============================================================

CREATE TABLE product_schema.product_faq (
    id             uuid         NOT NULL DEFAULT gen_random_uuid(),
    product_id     uuid         NOT NULL,
    user_id        uuid         NOT NULL,                -- ref: user_schema.users.id
    inquiry_type   varchar(30)  NOT NULL,
    title          varchar(255) NOT NULL,
    content        text         NOT NULL,
    is_private     boolean      NOT NULL DEFAULT false,
    status         varchar(20)  NOT NULL DEFAULT 'WAITING',
    answer_content text,
    answered_by    uuid,
    answered_at    timestamptz,
    deleted_at     timestamptz,
    created_at     timestamptz  NOT NULL,
    updated_at     timestamptz  NOT NULL,
    CONSTRAINT pk_product_faq PRIMARY KEY (id)
);
CREATE INDEX ON product_schema.product_faq (product_id, status, created_at);
CREATE INDEX ON product_schema.product_faq (user_id, created_at);
CREATE INDEX ON product_schema.product_faq (answered_by);

CREATE TABLE product_schema.product_inquiry_images (
    id               uuid         NOT NULL DEFAULT gen_random_uuid(),
    inquiry_id       uuid         NOT NULL,
    image_key        varchar(500) NOT NULL,
    origin_file_name varchar(255),
    sort_order       integer      NOT NULL DEFAULT 0,
    deleted_at       timestamptz,
    created_at       timestamptz  NOT NULL,
    CONSTRAINT pk_product_inquiry_images PRIMARY KEY (id)
);
CREATE INDEX ON product_schema.product_inquiry_images (inquiry_id, sort_order);

-- ============================================================
-- 랭킹 / 검색 분석
-- ============================================================

-- 주간 베스트 상품 (배치 생성, 최대 30위)
CREATE TABLE product_schema.weekly_best_products (
    id           uuid        NOT NULL DEFAULT gen_random_uuid(),
    week_start   date        NOT NULL,
    rank         smallint    NOT NULL,
    product_id   uuid        NOT NULL,
    sales_count  integer     NOT NULL,
    sales_amount integer     NOT NULL,
    created_at   timestamptz NOT NULL,
    CONSTRAINT pk_weekly_best_products PRIMARY KEY (id)
);
CREATE UNIQUE INDEX ON product_schema.weekly_best_products (week_start, rank);

-- 검색 로그 (대용량 — 직접 INSERT 금지, 버퍼링 후 bulk COPY. 월별 파티셔닝은 운영 단계에서 적용)
CREATE TABLE product_schema.product_search_logs (
    id                     bigserial    NOT NULL,
    keyword                varchar(200) NOT NULL,
    user_id                uuid,
    session_id             varchar(100),
    result_count           integer      NOT NULL DEFAULT 0,
    clicked_product_id     uuid,
    clicked_combination_id uuid,
    searched_at            timestamptz  NOT NULL,
    CONSTRAINT pk_product_search_logs PRIMARY KEY (id)
);
CREATE INDEX ON product_schema.product_search_logs USING BRIN (searched_at);
CREATE INDEX ON product_schema.product_search_logs (keyword, searched_at);
CREATE INDEX ON product_schema.product_search_logs (user_id, searched_at);
CREATE INDEX ON product_schema.product_search_logs (clicked_product_id, searched_at);

-- 검색어 일별 집계 (야간 배치. seller 대시보드 소스)
CREATE TABLE product_schema.search_keyword_daily_stats (
    id           bigserial    NOT NULL,
    date         date         NOT NULL,
    keyword      varchar(200) NOT NULL,
    seller_id    uuid,
    product_id   uuid,
    search_count integer      NOT NULL DEFAULT 0,
    click_count  integer      NOT NULL DEFAULT 0,
    CONSTRAINT pk_search_keyword_daily_stats PRIMARY KEY (id)
);
CREATE INDEX ON product_schema.search_keyword_daily_stats (date, seller_id, search_count);
CREATE INDEX ON product_schema.search_keyword_daily_stats (date, keyword, search_count);
CREATE INDEX ON product_schema.search_keyword_daily_stats (date, product_id, click_count);

-- ============================================================
-- 공통 (도메인 전용 outbox / audit)
-- ============================================================

CREATE TABLE product_schema.outbox_events (
    id             bigserial    NOT NULL,
    aggregate_type varchar(50)  NOT NULL,
    aggregate_id   uuid         NOT NULL,
    event_type     varchar(50)  NOT NULL,
    payload        jsonb        NOT NULL,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    processed_at   timestamptz,
    retry_count    integer      NOT NULL DEFAULT 0,
    last_error     text,
    CONSTRAINT pk_product_outbox_events PRIMARY KEY (id)
);
CREATE INDEX ON product_schema.outbox_events (processed_at, created_at);
CREATE INDEX ON product_schema.outbox_events (aggregate_type, aggregate_id);
CREATE INDEX ON product_schema.outbox_events (event_type, created_at);
CREATE INDEX ON product_schema.outbox_events (retry_count, created_at);

CREATE TABLE product_schema.audit_logs (
    id          uuid         NOT NULL DEFAULT gen_random_uuid(),
    actor_id    uuid,
    actor_type  varchar(10)  NOT NULL,
    action      varchar(100) NOT NULL,
    target_id   uuid,
    target_type varchar(50),
    detail      jsonb,
    ip_address  varchar(45),
    created_at  timestamptz  NOT NULL,
    CONSTRAINT pk_product_audit_logs PRIMARY KEY (id)
);
CREATE INDEX ON product_schema.audit_logs (actor_id, actor_type, created_at);
CREATE INDEX ON product_schema.audit_logs (action, created_at);
