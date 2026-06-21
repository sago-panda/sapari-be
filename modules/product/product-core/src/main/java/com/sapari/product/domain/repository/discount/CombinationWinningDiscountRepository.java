package com.sapari.product.domain.repository.discount;

import com.sapari.product.domain.model.discount.CombinationWinningDiscount;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link CombinationWinningDiscount} 영속 포트 — 조합당 현재 승자 할인 1개(파생 캐시).
 *
 * <p>행 존재=할인 있음, 행 없음=할인 없음. PK=combinationId(조합과 1:1)라 save는 존재 여부로 갱신/신규를 가른다.
 * 승자 선정 순서: priority DESC → 타깃 구체성(조합-레벨>상품-레벨) → 실제 할인액 DESC → policy_id DESC.
 */
public interface CombinationWinningDiscountRepository {

    /**
     * PK(combinationId)가 이미 있으면 갱신, 없으면 신규(앱 주입 PK).
     */
    CombinationWinningDiscount save(CombinationWinningDiscount winningDiscount);

    Optional<CombinationWinningDiscount> findById(UUID combinationId);

    /**
     * 특정 정책이 승자인 조합들. 정책 만료·비활성화 시 대체 정책으로 재해소할 대상 조회.
     */
    List<CombinationWinningDiscount> findByPolicyId(UUID policyId);
}
