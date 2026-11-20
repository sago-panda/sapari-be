package com.sapari.product.domain.exception;

/**
 * 같은 애그리거트(상품·옵션 조합)를 동시에 수정해 낙관적 락 충돌이 발생했을 때 던진다.
 *
 * <p>한 트랜잭션이 읽은 버전과 저장 시점의 행 버전이 달라, 그 사이 다른 트랜잭션이 먼저 수정한 경우다. 호출자는 최신 상태로 다시 읽어 재시도해야 한다
 * ({@link ProductErrorCode#CONCURRENT_MODIFICATION} → 409).
 */
public class ProductConcurrentModificationException extends ProductDomainException {

    /**
     * 디버그 메시지로 생성한다(클라이언트에는 카탈로그 메시지만 노출).
     *
     * @param debugMessage 로그용 상세(충돌 대상 식별자 등)
     */
    public ProductConcurrentModificationException(String debugMessage) {
        super(ProductErrorCode.CONCURRENT_MODIFICATION, debugMessage);
    }

    /**
     * 디버그 메시지와 원인 예외(영속 계층 낙관적 락 예외)로 생성한다.
     *
     * @param debugMessage 로그용 상세(충돌 대상 식별자 등)
     * @param cause        원인이 된 영속 계층 예외
     */
    public ProductConcurrentModificationException(String debugMessage, Throwable cause) {
        super(ProductErrorCode.CONCURRENT_MODIFICATION, debugMessage, cause);
    }
}
