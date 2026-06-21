package com.sapari.product.infrastructure.persistence.entity.product;

import com.sapari.product.domain.model.product.ProductOptionModel;
import com.sapari.product.domain.model.product.ProductStatus;

import com.sapari.storage.db.entity.UuidTimeEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 상품 원장(products)의 영속 엔티티 — Product 애그리거트 루트.
 *
 * <p>정렬·필터용 <b>비정규화 캐시 컬럼</b>(minPrice·hasStock·purchaseCount·avgRating·*Count)은 DB가 계산하지
 * 않고 앱이 갱신한다. 카테고리(bigint)·배송정책 등 참조는 물리 FK 없이 id 컬럼으로만 두며, 삭제는 deletedAt 소프트딜리트로 처리한다. status/optionModel은 도메인 enum을
 * 그대로 varchar로 저장한다(별도 엔티티 enum 없음).
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "products", schema = "product_schema")
public class ProductEntity extends UuidTimeEntity {

    private UUID sellerId;

    // ref: categories.id (bigint). 물리 FK 미사용.
    private Long categoryId;

    private String name;

    private String description;

    private Integer basePrice;

    @Enumerated(EnumType.STRING)
    private ProductStatus status;

    private UUID shippingPolicyId;

    private Integer additionalShippingFee;

    private String rejectionReason;

    private Instant deletedAt;

    private Integer minPrice;

    private Boolean hasStock;

    private Integer purchaseCount;

    private BigDecimal avgRating;

    private Integer reviewCount;

    private Integer viewCount;

    private Integer wishlistCount;

    @Enumerated(EnumType.STRING)
    private ProductOptionModel optionModel;

    private Instant searchIndexedAt;

    /**
     * 빌더 전용 생성자. JPA가 요구하는 protected 기본 생성자와 구분해 private으로 두고, MapStruct 매퍼·테스트 픽스처가 빌더로만 신규 생성/재구성하도록 강제한다.
     */
    @Builder
    private ProductEntity(UUID sellerId, Long categoryId, String name, String description, Integer basePrice,
                          ProductStatus status, UUID shippingPolicyId, Integer additionalShippingFee,
                          String rejectionReason,
                          Instant deletedAt, Integer minPrice, Boolean hasStock, Integer purchaseCount,
                          BigDecimal avgRating,
                          Integer reviewCount, Integer viewCount, Integer wishlistCount, ProductOptionModel optionModel,
                          Instant searchIndexedAt) {
        this.sellerId = sellerId;
        this.categoryId = categoryId;
        this.name = name;
        this.description = description;
        this.basePrice = basePrice;
        this.status = status;
        this.shippingPolicyId = shippingPolicyId;
        this.additionalShippingFee = additionalShippingFee;
        this.rejectionReason = rejectionReason;
        this.deletedAt = deletedAt;
        this.minPrice = minPrice;
        this.hasStock = hasStock;
        this.purchaseCount = purchaseCount;
        this.avgRating = avgRating;
        this.reviewCount = reviewCount;
        this.viewCount = viewCount;
        this.wishlistCount = wishlistCount;
        this.optionModel = optionModel;
        this.searchIndexedAt = searchIndexedAt;
    }

    /**
     * 판매자가 편집하는 상품 기본 정보(분류·이름·설명·기준가·배송정책·옵션 모델)를 일괄 갱신한다.
     */
    public void updateBasicInfo(Long categoryId, String name, String description, Integer basePrice,
                                UUID shippingPolicyId, Integer additionalShippingFee, ProductOptionModel optionModel) {
        this.categoryId = categoryId;
        this.name = name;
        this.description = description;
        this.basePrice = basePrice;
        this.shippingPolicyId = shippingPolicyId;
        this.additionalShippingFee = additionalShippingFee;
        this.optionModel = optionModel;
    }

    /**
     * 검수 상태와 반려 사유를 함께 변경한다(반려 시에만 사유가 채워지고, 그 외 상태에서는 null).
     */
    public void updateStatus(ProductStatus status, String rejectionReason) {
        this.status = status;
        this.rejectionReason = rejectionReason;
    }

    /**
     * 소프트딜리트 시각을 설정/해제한다(삭제는 물리 제거가 아닌 deletedAt 마킹).
     */
    public void updateDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }

    /**
     * 정렬·필터용 비정규화 캐시 컬럼을 일괄 갱신한다.
     *
     * <p>최저가·재고유무·구매수·평점·리뷰수·조회수·찜수는 DB가 계산하지 않고 앱이 집계해 채우는 캐시이므로,
     * 재계산 시점에 한 번에 묶어 갱신한다.
     */
    public void updateCacheColumns(Integer minPrice, Boolean hasStock, Integer purchaseCount,
                                   BigDecimal avgRating, Integer reviewCount, Integer viewCount,
                                   Integer wishlistCount) {
        this.minPrice = minPrice;
        this.hasStock = hasStock;
        this.purchaseCount = purchaseCount;
        this.avgRating = avgRating;
        this.reviewCount = reviewCount;
        this.viewCount = viewCount;
        this.wishlistCount = wishlistCount;
    }

    /**
     * 검색 색인에 반영된 시각을 기록한다(이후 변경분 색인 재처리 기준).
     */
    public void updateSearchIndexedAt(Instant searchIndexedAt) {
        this.searchIndexedAt = searchIndexedAt;
    }
}
