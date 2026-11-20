-- 판매자 상품 목록(GET /api/v1/seller/products) 커서 페이지네이션용 복합 인덱스.
-- 쿼리: WHERE seller_id = :s AND deleted_at IS NULL
--         AND (created_at < :c OR (created_at = :c AND id < :id))
--       ORDER BY created_at DESC, id DESC LIMIT :size + 1
-- 정렬 두 키(tie-break 포함)를 같은 방향으로 담아 정렬 없이 인덱스 순서대로 읽고 LIMIT에서 멈춘다.
-- 삭제 상품은 목록에 나오지 않으므로 부분 인덱스로 제외한다.
CREATE INDEX ON product_schema.products (seller_id, created_at DESC, id DESC) WHERE deleted_at IS NULL;
