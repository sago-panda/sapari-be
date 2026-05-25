package com.sapari.seller.domain.exception;

import lombok.Getter;

@Getter
public class SellerException extends RuntimeException {

    private final SellerErrorCode errorCode;

    public SellerException(SellerErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public SellerException(SellerErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }
}
