package com.sapari.product.infrastructure.persistence.entity.productgroup;

import com.sapari.storage.db.entity.UuidTimeEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 판매자 큐레이션 상품 그룹의 영속 엔티티 (table {@code product_group_sets}). 구성 상품은 {@code ProductGroupItemEntity}(자식)로 분리되며,
 * {@code sellerId}는 물리 FK 미사용.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "product_group_sets", schema = "product_schema")
public class ProductGroupSetEntity extends UuidTimeEntity {

    // ref: users.id. 물리 FK 미사용.
    private UUID sellerId;

    private String groupName;

    /**
     * 빌더 전용 생성자. JPA가 요구하는 protected 기본 생성자와 구분하여, MapStruct 매퍼·테스트 픽스처가 빌더로만 신규 생성/재구성하도록 한다.
     */
    @Builder
    public ProductGroupSetEntity(UUID sellerId, String groupName) {
        this.sellerId = sellerId;
        this.groupName = groupName;
    }

    /**
     * 그룹을 소유한 판매자를 갱신한다.
     */
    public void updateSellerId(UUID sellerId) {
        this.sellerId = sellerId;
    }

    /**
     * 큐레이션 그룹 이름을 갱신한다.
     */
    public void updateGroupName(String groupName) {
        this.groupName = groupName;
    }
}
