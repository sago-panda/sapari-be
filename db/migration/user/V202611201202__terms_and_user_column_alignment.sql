-- SPR-99/127 이 V1 을 직접 고쳤던 내용을 새 버전으로 옮긴다. V1 은 이미 적용된 환경이 있어 바꾸면 체크섬이 깨진다.
-- profile_image_key 축소는 URL 을 비우는 V202611201200 뒤에 와야 한다(URL 은 200 자를 넘을 수 있다).

ALTER TABLE user_schema.users
    ALTER COLUMN profile_image_key TYPE varchar(200);

-- id 는 애플리케이션이 채운다.
ALTER TABLE user_schema.withdrawn_user_retentions
    ALTER COLUMN id DROP DEFAULT;

-- 회원가입 시점의 약관 버전을 증적 row가 참조하기 위한 불변 기준 데이터.
-- 이미 증적에 사용된 row는 삭제/수정하지 않고, 약관 변경은 새 version row를 추가한다.
-- Sapari는 필수 동의를 PRIVACY 타입의 단일 약관 번들로 운영하며, 필수 내용 변경은 새 타입 추가가 아니라 같은 타입의 새 version으로 처리한다.
-- active=true는 예약 약관이 아니라 현재 유효한 약관 1개를 뜻하며, content_url은 당시 전문 위치를 가리킨다.
CREATE TABLE user_schema.terms (
    id              uuid         NOT NULL,
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
-- 약관 교체는 기존 active row 비활성화와 새 version active row 삽입을 단일 트랜잭션으로 수행해야 한다.
-- 분리 실행 시 active=true 약관 공백으로 가입 요청이 실패할 수 있으며, 새 active row의 effective_from은 활성화 시점 이하로 둔다.
CREATE UNIQUE INDEX uk_terms_type_active_true
    ON user_schema.terms (type)
    WHERE active = true;

-- 사용자가 가입 시점에 어떤 약관 버전에 동의/거부했는지 남기는 증적 테이블.
-- user_id와 terms_id는 FK를 두지 않는 soft reference다.
-- users hard delete와 증적 보존 정책이 충돌하지 않도록 정합성은 가입 서비스에서 보장한다.
-- MARKETING 같은 선택 약관은 agreed=false도 명시적 거부 증적으로 저장한다.
CREATE TABLE user_schema.user_terms_agreements (
    id          uuid        NOT NULL,
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
