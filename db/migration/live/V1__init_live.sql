-- live 도메인 초기 스키마 (설계 DDL 정렬판 + 합의된 변경 3건)
--  1) 미디어 = 합집합: LiveKit(sfu/egress/hls) + Ingress 송출(stream_type/ingress_id; streamKey는 미저장)
--  2) 상태 = 코드 모델 유지: SCHEDULED → LIVE → ENDED, SUSPENDED 측면 분기 (CANCELLED/force_ended 미채택)
--  3) live_products = DDL 구조 + live_price(최종가 캐시) 추가, 가격 불변·핀/순서 가변
-- ⚠️ 테이블/참조 컬럼명은 코드의 LiveRoomEntity(live_rooms)·LiveProductEntity(live_room_id)에 맞춰 정렬했다.
--    컬럼 필드 단위 대조(엔티티 ↔ DDL)는 별도 브랜치에서 진행한다.
CREATE SCHEMA IF NOT EXISTS live_schema;

-- ============================================================
-- 라이브 세션 원장
-- ============================================================
CREATE TABLE live_schema.live_rooms (
    id                       uuid         NOT NULL DEFAULT gen_random_uuid(),
    seller_id                uuid         NOT NULL,      -- ref: user_schema.users.id
    title                    varchar(100) NOT NULL,
    description              varchar(500),
    seller_nickname          varchar(50)  NOT NULL,      -- 판매자 닉네임 스냅샷 (도메인 create 필수)
    thumbnail_url            varchar(500),               -- 대표 썸네일 캐시 (커버 원천은 live_thumbnails)
    status                   varchar(20)  NOT NULL DEFAULT 'SCHEDULED',  -- SCHEDULED | LIVE | ENDED | SUSPENDED
    -- 송출 (합집합: WEBRTC=토큰 publish / RTMP=LiveKit Ingress)
    stream_type              varchar(10)  NOT NULL DEFAULT 'WEBRTC',  -- WEBRTC | RTMP
    sfu_room_id              varchar(255),               -- LiveKit room (공통)
    ingress_id               varchar(255),               -- RTMP Ingress 참조 (streamKey는 미저장 — LiveKit 보관, 재조회 시 listIngress)
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
    is_vod_public            boolean      NOT NULL DEFAULT true,
    created_at               timestamptz  NOT NULL,
    updated_at               timestamptz  NOT NULL,
    CONSTRAINT pk_live_rooms PRIMARY KEY (id)
);
CREATE INDEX ON live_schema.live_rooms (seller_id, status, created_at);
CREATE INDEX ON live_schema.live_rooms (status, scheduled_at);
CREATE INDEX ON live_schema.live_rooms (started_at);
CREATE INDEX ON live_schema.live_rooms (concurrent_viewers);
CREATE INDEX ON live_schema.live_rooms (deactivate_after);

-- ============================================================
-- 라이브 등록 상품 (최대 50개) — DDL 구조 + live_price 캐시
-- 가격 4필드(list/selling/discount_*/live_price)는 등록 후 불변, 핀·sort_order만 가변
-- ============================================================
CREATE TABLE live_schema.live_products (
    id                  uuid        NOT NULL DEFAULT gen_random_uuid(),
    live_room_id        uuid        NOT NULL,
    product_id          uuid        NOT NULL,                -- ref: product_schema.products.id
    original_price      integer     NOT NULL,                -- 정가 (취소선 비교 표시가)
    discount_price      integer     NOT NULL,                -- 일반 판매가 (등록 시점 스냅샷)
    discount_type       varchar(15),                         -- RATE | FIXED_AMOUNT. NULL=라이브 할인 없음
    discount_value      integer,
    live_discount_price integer     NOT NULL,                -- 라이브 최종가 캐시 = discount_price − 할인 (등록 시 1회 산출, 결제 사용)
    sort_order          integer     NOT NULL DEFAULT 0,
    is_pinned           boolean     NOT NULL DEFAULT false,
    pinned_at           timestamptz,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    CONSTRAINT pk_live_products PRIMARY KEY (id)
);
CREATE INDEX ON live_schema.live_products (live_room_id, sort_order);
CREATE INDEX ON live_schema.live_products (live_room_id, is_pinned);
CREATE INDEX ON live_schema.live_products (product_id);

-- 라이브 중 상품 핀/언핀 이력. elapsed_seconds로 VOD 리플레이 재현
CREATE TABLE live_schema.live_product_pin_histories (
    id              uuid        NOT NULL DEFAULT gen_random_uuid(),
    live_room_id uuid        NOT NULL,
    live_product_id uuid        NOT NULL,
    action          varchar(10) NOT NULL,                -- PIN | UNPIN
    elapsed_seconds integer     NOT NULL,
    created_at      timestamptz NOT NULL,
    CONSTRAINT pk_live_product_pin_histories PRIMARY KEY (id)
);
CREATE INDEX ON live_schema.live_product_pin_histories (live_room_id, elapsed_seconds);

-- ============================================================
-- 채팅 · 모더레이션
-- ============================================================

-- 라이브 채팅. 구매자↔판매자 전용. elapsed_seconds로 VOD 리플레이 동기화
CREATE TABLE live_schema.live_chats (
    id              uuid         NOT NULL DEFAULT gen_random_uuid(),
    live_room_id uuid         NOT NULL,
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
CREATE INDEX ON live_schema.live_chats (live_room_id, elapsed_seconds);
CREATE INDEX ON live_schema.live_chats (live_room_id, created_at);
CREATE INDEX ON live_schema.live_chats (user_id, live_room_id);

-- 세션 강퇴 (append-only 순수 증거 로그 — 밴 상태 없음. 세션 종료 시 자동 해제)
CREATE TABLE live_schema.chat_kick_log (
    id                 uuid        NOT NULL DEFAULT gen_random_uuid(),
    user_id            uuid        NOT NULL,             -- 강퇴 대상 (soft ref: user_schema.users.id)
    live_room_id       uuid        NOT NULL,             -- 발생 라이브 (soft ref: live_schema.live_rooms.id)
    kicked_by_id       uuid        NOT NULL,
    kicked_by_role     varchar(16) NOT NULL,
    triggering_message text        NOT NULL,             -- 원문 스냅샷 (증거 박제)
    kicked_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_chat_kick_log PRIMARY KEY (id),
    CONSTRAINT uk_chat_kick_log_user_room UNIQUE (user_id, live_room_id),  -- 중복 강퇴 멱등
    CONSTRAINT chk_kick_by_role CHECK (kicked_by_role IN ('SELLER', 'ADMIN'))
);
CREATE INDEX ON live_schema.chat_kick_log (user_id, kicked_at DESC);

-- 플랫폼 밴 (전체 차단 — scope 없음 = 항상 전체. 판매자 한정 밴 없음)
-- banned_by_id: 자동 escalation=SYSTEM UUID / 수동=ADMIN id (판매자 불가 — banned_by_role 컬럼이
--   없어 DB CHECK로는 미강제. 앱 레벨(ADMIN/SYSTEM만 호출 가능)에서 보장)
-- 활성 판정: expires_at IS NULL OR expires_at > now(). un-ban = 행 DELETE.
-- Redis 미러: banned:{userId} 단일 키.
-- 자동 escalation(2년 롤링 글로벌 강퇴 카운트): 3회→1주 / 6회→1달 / 9회→1년 / 12회+→영구
CREATE TABLE live_schema.chat_ban (
    id           uuid        NOT NULL DEFAULT gen_random_uuid(),
    user_id      uuid        NOT NULL,
    banned_by_id uuid        NOT NULL,                   -- 자동=SYSTEM UUID / 수동=ADMIN id
    expires_at   timestamptz,                            -- NULL=영구
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_chat_ban PRIMARY KEY (id)
);
CREATE INDEX ON live_schema.chat_ban (user_id, expires_at);

-- 욕설/화이트리스트 사전 (글로벌 1개 — 판매자 커스텀 없음)
-- 3계층: Postgres(출처) → Redis Pub/Sub(변경 전파) → Pod 메모리 trie(런타임 평가)
CREATE TABLE live_schema.chat_filter_word (
    id         uuid        NOT NULL DEFAULT gen_random_uuid(),
    kind       varchar(16) NOT NULL,                     -- PROFANITY | WHITELIST
    word       varchar(64) NOT NULL,
    created_by uuid        NOT NULL,                     -- 관리자 id / 시드=SYSTEM UUID
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_chat_filter_word PRIMARY KEY (id),
    CONSTRAINT uk_chat_filter_word UNIQUE (kind, word),
    CONSTRAINT chk_filter_kind CHECK (kind IN ('PROFANITY', 'WHITELIST'))
);

-- ============================================================
-- 공지 · 썸네일 · 시청 · 좋아요 · 알림신청 · 클릭 이벤트
-- ============================================================

-- 라이브 공지 이력. 화면 상단 8초 노출, elapsed_seconds로 리플레이 재현
CREATE TABLE live_schema.live_announcements (
    id              uuid         NOT NULL DEFAULT gen_random_uuid(),
    live_room_id uuid         NOT NULL,
    type            varchar(15)  NOT NULL,
    content         varchar(500) NOT NULL,
    live_product_id uuid,                                -- 참조 상품 (이력 보존 — 앱에서 삭제 제한)
    elapsed_seconds integer      NOT NULL,
    created_at      timestamptz  NOT NULL,
    CONSTRAINT pk_live_announcements PRIMARY KEY (id)
);
CREATE INDEX ON live_schema.live_announcements (live_room_id, elapsed_seconds);

-- 5분 간격 자동 캡처 썸네일. is_cover=true가 목록 대표·VOD 커버 (판매자 업로드 포함)
CREATE TABLE live_schema.live_thumbnails (
    id              uuid         NOT NULL DEFAULT gen_random_uuid(),
    live_room_id uuid         NOT NULL,
    image_key       varchar(500) NOT NULL,
    is_cover        boolean      NOT NULL DEFAULT false,
    elapsed_seconds integer      NOT NULL,
    captured_at     timestamptz  NOT NULL,
    created_at      timestamptz  NOT NULL,
    CONSTRAINT pk_live_thumbnails PRIMARY KEY (id)
);
CREATE INDEX ON live_schema.live_thumbnails (live_room_id, elapsed_seconds);
CREATE INDEX ON live_schema.live_thumbnails (live_room_id, is_cover);

-- 시청자 입장·퇴장 이벤트. 비회원은 user_id NULL. unique_viewers 집계 원천
CREATE TABLE live_schema.live_viewer_sessions (
    id                     uuid        NOT NULL DEFAULT gen_random_uuid(),
    live_room_id        uuid        NOT NULL,
    user_id                uuid,
    joined_at              timestamptz NOT NULL,
    left_at                timestamptz,
    watch_duration_seconds integer,
    created_at             timestamptz NOT NULL,
    CONSTRAINT pk_live_viewer_sessions PRIMARY KEY (id)
);
CREATE INDEX ON live_schema.live_viewer_sessions (live_room_id, joined_at);
CREATE INDEX ON live_schema.live_viewer_sessions (user_id, live_room_id);
CREATE INDEX ON live_schema.live_viewer_sessions (live_room_id, left_at);

-- 동시 시청자 시계열 스냅샷 (1분 간격). 판매자 대시보드 그래프 소스
CREATE TABLE live_schema.live_viewer_counts (
    id                 uuid        NOT NULL DEFAULT gen_random_uuid(),
    live_room_id    uuid        NOT NULL,
    concurrent_viewers integer     NOT NULL,
    elapsed_seconds    integer     NOT NULL,
    recorded_at        timestamptz NOT NULL,
    CONSTRAINT pk_live_viewer_counts PRIMARY KEY (id)
);
CREATE UNIQUE INDEX ON live_schema.live_viewer_counts (live_room_id, elapsed_seconds);

-- 라이브 좋아요. 사용자당 세션당 1회. like_count와 동기화
CREATE TABLE live_schema.live_likes (
    id              uuid        NOT NULL DEFAULT gen_random_uuid(),
    live_room_id uuid        NOT NULL,
    user_id         uuid        NOT NULL,
    created_at      timestamptz NOT NULL,
    CONSTRAINT pk_live_likes PRIMARY KEY (id)
);
CREATE UNIQUE INDEX ON live_schema.live_likes (live_room_id, user_id);
CREATE INDEX ON live_schema.live_likes (live_room_id);

-- 예약 라이브 알림 신청. 시작 10분 전·시작 시 두 번 발송
CREATE TABLE live_schema.live_session_subscriptions (
    id                   uuid        NOT NULL DEFAULT gen_random_uuid(),
    user_id              uuid        NOT NULL,
    live_room_id      uuid        NOT NULL,
    notified_before      boolean     NOT NULL DEFAULT false,
    notified_before_at   timestamptz,
    notified_on_start    boolean     NOT NULL DEFAULT false,
    notified_on_start_at timestamptz,
    cancelled_at         timestamptz,                    -- NOT NULL이면 발송 대상 제외
    created_at           timestamptz NOT NULL,
    CONSTRAINT pk_live_session_subscriptions PRIMARY KEY (id)
);
CREATE UNIQUE INDEX ON live_schema.live_session_subscriptions (user_id, live_room_id);
CREATE INDEX ON live_schema.live_session_subscriptions (live_room_id, notified_before);
CREATE INDEX ON live_schema.live_session_subscriptions (live_room_id, notified_on_start);

-- 라이브 상품 클릭 이벤트 시계열 (버퍼링 후 배치 INSERT. 파티셔닝은 운영 단계 과제)
CREATE TABLE live_schema.live_product_click_events (
    id              uuid        NOT NULL DEFAULT gen_random_uuid(),
    live_room_id uuid        NOT NULL,
    live_product_id uuid        NOT NULL,
    user_id         uuid,
    source          varchar(15) NOT NULL,
    elapsed_seconds integer     NOT NULL,
    clicked_at      timestamptz NOT NULL,
    CONSTRAINT pk_live_product_click_events PRIMARY KEY (id)
);
CREATE INDEX ON live_schema.live_product_click_events (live_room_id, clicked_at);
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
