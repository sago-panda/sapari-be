package com.sapari.seller.domain.exception;

import com.sapari.common.core.exception.BusinessException;

public class SellerException extends BusinessException {

    public SellerException(SellerErrorCode errorCode) {
        super(errorCode);
    }

    public SellerException(SellerErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }

    @Override
    public SellerErrorCode getErrorCode() {
        return (SellerErrorCode) super.getErrorCode();
    }
}
