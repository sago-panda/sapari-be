-- SPR-213 판매자 라이브 권한. seller 모듈이 뼈대뿐이라 live 가 갖는다.
-- 행이 없으면 허용 — 관리자가 한 번이라도 바꾼 판매자만 행이 생긴다. 누가 왜 바꿨는지는 audit_logs 에 남긴다.
CREATE TABLE live_schema.live_seller_permission (
    seller_id    uuid        NOT NULL,
    live_enabled boolean     NOT NULL,
    updated_at   timestamptz NOT NULL,
    CONSTRAINT pk_live_seller_permission PRIMARY KEY (seller_id)
);
