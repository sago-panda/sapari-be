package com.sapari.product.command;

import java.util.List;

/**
 * 조합 추가금 룰 입력. 지정한 옵션값들을 모두 포함하는 조합(부분조건 매칭)에 금액을 가산한다.
 *
 * <p>등록 시점엔 옵션값 UUID가 없으므로 옵션값 커맨드의 {@code ref}로 가리킨다. 서버가 저장 후 UUID로 해석한다.
 *
 * @param valueRefs 매칭 조건 옵션값 ref 목록(타입당 최대 1개)
 * @param amount    가산 금액(원, 0 이상)
 */
public record SurchargeRuleCommand(
        List<String> valueRefs,
        int amount
) {
}
