package com.sapari.product.domain.repository.discount;

import com.sapari.product.domain.model.discount.DiscountPolicy;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link DiscountPolicy} 애그리거트의 영속 포트.
 *
 * <p>정책은 적용 대상 상품/조합을 id 리스트로 보유한다(자식 매핑). 실제 조합별 승자 할인은
 * 정책 변경 시점에 {@code combination_winning_discounts}에 write-time으로 해소·저장된다.
 */
public interface DiscountPolicyRepository {

    /**
     * upsert(id==null→INSERT). 대상 상품·조합 매핑도 함께 교체 저장한다.
     */
    DiscountPolicy save(DiscountPolicy policy);

    Optional<DiscountPolicy> findById(UUID id);

    /**
     * 활성(is_active=true) 정책 전체. 승자 재해소 시 후보 정책 집합으로 사용.
     */
    List<DiscountPolicy> findAllActive();
}
