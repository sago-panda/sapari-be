-- SPR-99 가 V1 을 직접 고쳤던 내용을 새 버전으로 옮긴다. V1 은 이미 적용된 환경이 있어 바꾸면 체크섬이 깨진다.
-- store_name 을 20 자로 줄이므로 더 긴 값이 있으면 여기서 실패한다(자동으로 자르지 않는다).

ALTER TABLE seller_schema.seller_profiles
    ALTER COLUMN status TYPE varchar(20),
    ALTER COLUMN store_name TYPE varchar(20);

CREATE UNIQUE INDEX ON seller_schema.seller_profiles (store_name);
CREATE UNIQUE INDEX ON seller_schema.seller_profiles (business_number);
