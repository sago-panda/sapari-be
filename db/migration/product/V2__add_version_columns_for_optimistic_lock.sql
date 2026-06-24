-- 상품·옵션 조합 동시 수정 시 lost update를 막기 위한 낙관적 락(@Version) 버전 컬럼.
-- 기존 행은 0으로 시작하며, 이후 갱신마다 Hibernate가 증가시킨다.
ALTER TABLE product_schema.products
    ADD COLUMN version bigint NOT NULL DEFAULT 0;

ALTER TABLE product_schema.product_option_combinations
    ADD COLUMN version bigint NOT NULL DEFAULT 0;
