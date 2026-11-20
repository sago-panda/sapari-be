package com.sapari.product.domain.model.category;

import java.time.Instant;
import lombok.Builder;

/**
 * 상품 카테고리 트리의 도메인 모델 (ltree 노드).
 *
 * <p>불변 record — 변경은 새 인스턴스를 반환한다. {@code id}는 영속(INSERT) 후 DB가 채우는
 * bigint IDENTITY라 {@link #create} 직후엔 {@code null}이며, 이 값이 ltree {@code path} 라벨로도 직접 쓰인다. {@code parentId}는 물리 FK 없이
 * 자기참조로 트리를 구성한다.
 */
@Builder(toBuilder = true)
public record Category(
        Long id,
        Long parentId,
        String path,
        String name,
        Short depth,
        Integer sortOrder,
        Boolean isActive,
        Instant createdAt,
        Instant updatedAt
) {

    public Category {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path는 필수입니다.");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name은 필수입니다.");
        }
        if (depth == null) {
            throw new IllegalArgumentException("depth는 필수입니다.");
        }
    }

    /**
     * 신규 카테고리를 생성한다. path·name·depth는 필수이며 sortOrder/isActive는 누락 시 각각 {@code 0}/{@code true}로 채운다. id와 생성·수정 시각은 영속
     * 시점에 채워진다.
     */
    public static Category create(Long parentId, String path, String name, Short depth,
                                  Integer sortOrder, Boolean isActive) {

        return Category.builder()
                .parentId(parentId)
                .path(path)
                .name(name)
                .depth(depth)
                .sortOrder(sortOrder == null ? 0 : sortOrder)
                .isActive(isActive == null ? Boolean.TRUE : isActive)
                .build();
    }
}
