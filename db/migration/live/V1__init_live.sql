-- live 도메인 초기 스키마 (설계 DDL 정렬판 + 합의된 변경 3건)
--  1) 미디어 = 합집합: LiveKit(sfu/egress/hls) + Ingress 송출(stream_type/stream_key/ingress_id)
--  2) 상태 = 코드 모델 유지: SCHEDULED → LIVE → ENDED, SUSPENDED 측면 분기 (CANCELLED/force_ended 미채택)
--  3) live_products = DDL 구조 + live_price(최종가 캐시) 추가, 가격 불변·핀/순서 가변
-- ⚠️ 이 V1은 live 엔티티 리팩토링(LiveRoomEntity→live_sessions 정렬)과 함께 적용해야 한다.
--    리팩토링 전까지 기존 엔티티(live_rooms 매핑)는 ddl validate에 실패한다.
CREATE SCHEMA IF NOT EXISTS live_schema;

-- ============================================================
-- 라이브 세션 원장
-- ============================================================
CREATE TABLE live_schema.live_sessions (
    id                       uuid         NOT NULL DEFAULT gen_random_uuid(),
    seller_id                uuid         NOT NULL,      -- ref: user_schema.users.id
    title                    varchar(100) NOT NULL,
    description              varchar(500),
    status                   varchar(20)  NOT NULL DEFAULT 'SCHEDULED',  -- SCHEDULED | LIVE | ENDED | SUSPENDED
    -- 송출 (합집합: WEBRTC=토큰 publish / RTMP=LiveKit Ingress)
    stream_type              varchar(10)  NOT NULL,      -- WEBRTC | RTMP
    sfu_room_id              varchar(255),               -- LiveKit room (공통)
    ingress_id               varchar(255),               -- RTMP일 때 LiveKit Ingress 식별자
    stream_key               varchar(255),               -- RTMP 송출 키 (자격증명 — 1회 노출·로그 금지)
    stream_key_issued_at     timestamptz,
    -- 시청 (HLS Egress, 공통)
    egress_id                varchar(255),
    hls_url                  varchar(255),
    hls_archive_url          varchar(255),
    -- 일정·운영
    scheduled_at             timestamptz,
    scheduled_end_at         timestamptz,
    max_duration_seconds     integer      NOT NULL DEFAULT 7200,
    disconnect_grace_seconds integer      NOT NULL DEFAULT 300,
    started_at               timestamptz,
    ended_at                 timestamptz,
    deactivate_after         timestamptz,
    -- 정지 (코드 상태머신 유지)
    suspended_reason         text,
    suspended_at             timestamptz,
    -- 집계 캐시
    peak_viewers             integer      NOT NULL DEFAULT 0,
    concurrent_viewers       integer      NOT NULL DEFAULT 0,
    unique_viewers           integer      NOT NULL DEFAULT 0,  -- live_viewer_sessions 집계 캐시
    chat_count               integer      NOT NULL DEFAULT 0,
    chat_disabled            boolean      NOT NULL DEFAULT false,
    like_count               integer      NOT NULL DEFAULT 0,
    -- VOD
    vod_key                  varchar(500),
    vod_duration_seconds     integer,
    vod_status               varchar(20)  NOT NULL DEFAULT 'NONE',
    vod_failed_reason        text,
    vod_is_public            boolean      NOT NULL DEFAULT true,
    created_at               timestamptz  NOT NULL,
    updated_at               timestamptz  NOT NULL,
    CONSTRAINT pk_live_sessions PRIMARY KEY (id)
);
CREATE INDEX ON live_schema.live_sessions (seller_id, status, created_at);
CREATE INDEX ON live_schema.live_sessions (status, scheduled_at);
CREATE INDEX ON live_schema.live_sessions (stream_key);
CREATE INDEX ON live_schema.live_sessions (started_at);
CREATE INDEX ON live_schema.live_sessions (concurrent_viewers);
CREATE INDEX ON live_schema.live_sessions (deactivate_after);

-- ============================================================
-- 라이브 등록 상품 (최대 50개) — DDL 구조 + live_price 캐시
-- 가격 4필드(list/selling/discount_*/live_price)는 등록 후 불변, 핀·sort_order만 가변
-- ============================================================
CREATE TABLE live_schema.live_products (
    id              uuid        NOT NULL DEFAULT gen_random_uuid(),
    live_session_id uuid        NOT NULL,
    product_id      uuid        NOT NULL,                -- ref: product_schema.products.id
    list_price      integer,                             -- 비교 표시가 (취소선). NULL=미설정
    selling_price   integer     NOT NULL,                -- 등록 시점 판매가 스냅샷
    discount_type   varchar(15),                         -- RATE | FIXED_AMOUNT. NULL=라이브 할인 없음
    discount_value  integer,
    live_price      integer     NOT NULL,                -- 최종가 캐시 = selling_price − 할인 (등록 시 1회 산출, 결제 사용)
    sort_order      integer     NOT NULL DEFAULT 0,
    is_pinned       boolean     NOT NULL DEFAULT false,
    pinned_at       timestamptz,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    CONSTRAINT pk_live_products PRIMARY KEY (id)
);
CREATE INDEX ON live_schema.live_products (live_session_id, sort_order);
CREATE INDEX ON live_schema.live_products (live_session_id, is_pinned);
CREATE INDEX ON live_schema.live_products (product_id);

-- 라이브 중 상품 핀/언핀 이력. elapsed_seconds로 VOD 리플레이 재현
CREATE TABLE live_schema.live_product_pin_histories (
    id              uuid        NOT NULL DEFAULT gen_random_uuid(),
    live_session_id uuid        NOT NULL,
    live_product_id uuid        NOT NULL,
    action          varchar(10) NOT NULL,                -- PIN | UNPIN
    elapsed_seconds integer     NOT NULL,
    created_at      timestamptz NOT NULL,
    CONSTRAINT pk_live_product_pin_histories PRIMARY KEY (id)
);
CREATE INDEX ON live_schema.live_product_pin_histories (live_session_id, elapsed_seconds);

-- ============================================================
-- 채팅 · 모더레이션
-- ============================================================

-- 라이브 채팅. 구매자↔판매자 전용. elapsed_seconds로 VOD 리플레이 동기화
CREATE TABLE live_schema.live_chats (
    id              uuid         NOT NULL DEFAULT gen_random_uuid(),
    live_session_id uuid         NOT NULL,
    user_id         uuid         NOT NULL,               -- ref: user_schema.users.id
    message         varchar(200) NOT NULL,
    type            varchar(15)  NOT NULL DEFAULT 'NORMAL',
    is_deleted      boolean      NOT NULL DEFAULT false,
    deleted_by_id   uuid,
    deleted_by_type varchar(10),
    deleted_at      timestamptz,
    elapsed_seconds integer      NOT NULL,
    created_at      timestamptz  NOT NULL,
    CONSTRAINT pk_live_chats PRIMARY KEY (id)
);
CREATE INDEX ON live_schema.live_chats (live_session_id, elapsed_seconds);
CREATE INDEX ON live_schema.live_chats (live_session_id, created_at);
CREATE INDEX ON live_schema.live_chats (user_id, live_session_id);

-- 세션 단위 채팅 강퇴 로그 (append-only 순수 증거 로그 — 밴 상태 없음. 세션 종료 시 자동 해제)
CREATE TABLE live_schema.chat_kick_log (
    id                 bigserial   NOT NULL,
    user_id            uuid        NOT NULL,             -- soft ref: user_schema.users.id
    live_session_id    uuid        NOT NULL,             -- soft ref (원안 room_id — 파일 내 일관성 위해 통일)
    kicked_by_id       uuid        NOT NULL,
    kicked_by_role     varchar(16) NOT NULL,
    triggering_message text        NOT NULL,             -- 강퇴 유발 원문 스냅샷 (증거 보존)
    kicked_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_chat_kick_log PRIMARY KEY (id),
    CONSTRAINT uk_chat_kick_log_user_session UNIQUE (user_id, live_session_id),  -- 세션 멱등
    CONSTRAINT chk_kick_by_role CHECK (kicked_by_role IN ('SELLER', 'ADMIN'))
);

-- 유저-레벨 밴 상태 (세션을 넘어 유지). SELLER=해당 판매자 라이브 한정 / PLATFORM=전체 금지
-- 밴 판정: expires_at IS NULL(영구) OR expires_at > now(). 해제 = 행 삭제 또는 expires_at 갱신
CREATE TABLE live_schema.chat_ban (
    id             bigserial   NOT NULL,
    user_id        uuid        NOT NULL,                 -- 밴 대상
    scope          varchar(16) NOT NULL,
    seller_id      uuid,                                 -- scope=SELLER일 때만 (그 판매자 라이브 한정)
    banned_by_id   uuid        NOT NULL,
    banned_by_role varchar(16) NOT NULL,
    expires_at     timestamptz,                          -- NULL=영구
    created_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_chat_ban PRIMARY KEY (id),
    CONSTRAINT chk_ban_scope        CHECK (scope IN ('SELLER', 'PLATFORM')),
    CONSTRAINT chk_ban_by_role      CHECK (banned_by_role IN ('SELLER', 'ADMIN')),
    CONSTRAINT chk_ban_scope_seller CHECK ((scope = 'SELLER') = (seller_id IS NOT NULL)),
    CONSTRAINT chk_platform_ban_admin_only CHECK (scope <> 'PLATFORM' OR banned_by_role = 'ADMIN')  -- ★ 플랫폼 밴은 ADMIN만
);
CREATE INDEX ON live_schema.chat_ban (user_id, expires_at);
CREATE INDEX ON live_schema.chat_ban (seller_id, expires_at);

-- 채팅 금칙어/허용 필터 사전. WHITELIST 우선 → PROFANITY 평가 → action 적용
CREATE TABLE live_schema.chat_filter_word (
    id         uuid         NOT NULL DEFAULT gen_random_uuid(),
    kind       varchar(10)  NOT NULL DEFAULT 'PROFANITY',  -- PROFANITY | WHITELIST
    scope      varchar(10)  NOT NULL,                    -- GLOBAL | SELLER
    seller_id  uuid,                                     -- scope=SELLER일 때만
    word       varchar(100) NOT NULL,                    -- 소문자·공백 정규화 후 저장
    match_type varchar(10)  NOT NULL DEFAULT 'CONTAINS', -- EXACT | CONTAINS | REGEX
    action     varchar(10)  NOT NULL DEFAULT 'BLOCK',    -- BLOCK | MASK (WHITELIST는 무시)
    is_active  boolean      NOT NULL DEFAULT true,
    created_by uuid,                                     -- NULL=시스템 시드
    created_at timestamptz  NOT NULL,
    updated_at timestamptz  NOT NULL,
    CONSTRAINT pk_chat_filter_word PRIMARY KEY (id),
    CONSTRAINT chk_filter_scope_seller CHECK ((scope = 'SELLER') = (seller_id IS NOT NULL))
);
CREATE INDEX ON live_schema.chat_filter_word (scope, is_active);
CREATE INDEX ON live_schema.chat_filter_word (seller_id, is_active);
CREATE INDEX ON live_schema.chat_filter_word (kind, scope, seller_id, word);

-- ============================================================
-- 공지 · 썸네일 · 시청 · 좋아요 · 알림신청 · 클릭 이벤트
-- ============================================================

-- 라이브 공지 이력. 화면 상단 8초 노출, elapsed_seconds로 리플레이 재현
CREATE TABLE live_schema.live_announcements (
    id              uuid         NOT NULL DEFAULT gen_random_uuid(),
    live_session_id uuid         NOT NULL,
    type            varchar(15)  NOT NULL,
    content         varchar(500) NOT NULL,
    live_product_id uuid,                                -- 참조 상품 (이력 보존 — 앱에서 삭제 제한)
    elapsed_seconds integer      NOT NULL,
    created_at      timestamptz  NOT NULL,
    CONSTRAINT pk_live_announcements PRIMARY KEY (id)
);
CREATE INDEX ON live_schema.live_announcements (live_session_id, elapsed_seconds);

-- 5분 간격 자동 캡처 썸네일. is_cover=true가 목록 대표·VOD 커버 (판매자 업로드 포함)
CREATE TABLE live_schema.live_thumbnails (
    id              uuid         NOT NULL DEFAULT gen_random_uuid(),
    live_session_id uuid         NOT NULL,
    image_key       varchar(500) NOT NULL,
    is_cover        boolean      NOT NULL DEFAULT false,
    elapsed_seconds integer      NOT NULL,
    captured_at     timestamptz  NOT NULL,
    created_at      timestamptz  NOT NULL,
    CONSTRAINT pk_live_thumbnails PRIMARY KEY (id)
);
CREATE INDEX ON live_schema.live_thumbnails (live_session_id, elapsed_seconds);
CREATE INDEX ON live_schema.live_thumbnails (live_session_id, is_cover);

-- 시청자 입장·퇴장 이벤트. 비회원은 user_id NULL. unique_viewers 집계 원천
CREATE TABLE live_schema.live_viewer_sessions (
    id                     uuid        NOT NULL DEFAULT gen_random_uuid(),
    live_session_id        uuid        NOT NULL,
    user_id                uuid,
    joined_at              timestamptz NOT NULL,
    left_at                timestamptz,
    watch_duration_seconds integer,
    created_at             timestamptz NOT NULL,
    CONSTRAINT pk_live_viewer_sessions PRIMARY KEY (id)
);
CREATE INDEX ON live_schema.live_viewer_sessions (live_session_id, joined_at);
CREATE INDEX ON live_schema.live_viewer_sessions (user_id, live_session_id);
CREATE INDEX ON live_schema.live_viewer_sessions (live_session_id, left_at);

-- 동시 시청자 시계열 스냅샷 (1분 간격). 판매자 대시보드 그래프 소스
CREATE TABLE live_schema.live_viewer_counts (
    id                 uuid        NOT NULL DEFAULT gen_random_uuid(),
    live_session_id    uuid        NOT NULL,
    concurrent_viewers integer     NOT NULL,
    elapsed_seconds    integer     NOT NULL,
    recorded_at        timestamptz NOT NULL,
    CONSTRAINT pk_live_viewer_counts PRIMARY KEY (id)
);
CREATE UNIQUE INDEX ON live_schema.live_viewer_counts (live_session_id, elapsed_seconds);

-- 라이브 좋아요. 사용자당 세션당 1회. like_count와 동기화
CREATE TABLE live_schema.live_likes (
    id              uuid        NOT NULL DEFAULT gen_random_uuid(),
    live_session_id uuid        NOT NULL,
    user_id         uuid        NOT NULL,
    created_at      timestamptz NOT NULL,
    CONSTRAINT pk_live_likes PRIMARY KEY (id)
);
CREATE UNIQUE INDEX ON live_schema.live_likes (live_session_id, user_id);
CREATE INDEX ON live_schema.live_likes (live_session_id);

-- 예약 라이브 알림 신청. 시작 10분 전·시작 시 두 번 발송
CREATE TABLE live_schema.live_session_subscriptions (
    id                   uuid        NOT NULL DEFAULT gen_random_uuid(),
    user_id              uuid        NOT NULL,
    live_session_id      uuid        NOT NULL,
    notified_before      boolean     NOT NULL DEFAULT false,
    notified_before_at   timestamptz,
    notified_on_start    boolean     NOT NULL DEFAULT false,
    notified_on_start_at timestamptz,
    cancelled_at         timestamptz,                    -- NOT NULL이면 발송 대상 제외
    created_at           timestamptz NOT NULL,
    CONSTRAINT pk_live_session_subscriptions PRIMARY KEY (id)
);
CREATE UNIQUE INDEX ON live_schema.live_session_subscriptions (user_id, live_session_id);
CREATE INDEX ON live_schema.live_session_subscriptions (live_session_id, notified_before);
CREATE INDEX ON live_schema.live_session_subscriptions (live_session_id, notified_on_start);

-- 라이브 상품 클릭 이벤트 시계열 (버퍼링 후 배치 INSERT. 파티셔닝은 운영 단계 과제)
CREATE TABLE live_schema.live_product_click_events (
    id              uuid        NOT NULL DEFAULT gen_random_uuid(),
    live_session_id uuid        NOT NULL,
    live_product_id uuid        NOT NULL,
    user_id         uuid,
    source          varchar(15) NOT NULL,
    elapsed_seconds integer     NOT NULL,
    clicked_at      timestamptz NOT NULL,
    CONSTRAINT pk_live_product_click_events PRIMARY KEY (id)
);
CREATE INDEX ON live_schema.live_product_click_events (live_session_id, clicked_at);
CREATE INDEX ON live_schema.live_product_click_events (live_product_id);

-- ============================================================
-- 공통 (도메인 전용 outbox / audit)
-- ============================================================

CREATE TABLE live_schema.outbox_events (
    id             bigserial    NOT NULL,
    aggregate_type varchar(50)  NOT NULL,
    aggregate_id   uuid         NOT NULL,
    event_type     varchar(50)  NOT NULL,
    payload        jsonb        NOT NULL,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    processed_at   timestamptz,
    retry_count    integer      NOT NULL DEFAULT 0,
    last_error     text,
    CONSTRAINT pk_live_outbox_events PRIMARY KEY (id)
);
CREATE INDEX ON live_schema.outbox_events (processed_at, created_at);
CREATE INDEX ON live_schema.outbox_events (aggregate_type, aggregate_id);
CREATE INDEX ON live_schema.outbox_events (event_type, created_at);
CREATE INDEX ON live_schema.outbox_events (retry_count, created_at);

CREATE TABLE live_schema.audit_logs (
    id          uuid         NOT NULL DEFAULT gen_random_uuid(),
    actor_id    uuid,
    actor_type  varchar(10)  NOT NULL,
    action      varchar(100) NOT NULL,
    target_id   uuid,
    target_type varchar(50),
    detail      jsonb,
    ip_address  varchar(45),
    created_at  timestamptz  NOT NULL,
    CONSTRAINT pk_live_audit_logs PRIMARY KEY (id)
);
CREATE INDEX ON live_schema.audit_logs (actor_id, actor_type, created_at);
CREATE INDEX ON live_schema.audit_logs (action, created_at);
