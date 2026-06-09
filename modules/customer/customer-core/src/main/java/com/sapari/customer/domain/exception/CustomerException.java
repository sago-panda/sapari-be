package com.sapari.customer.domain.exception;

import com.sapari.common.core.exception.BusinessException;

public class CustomerException extends BusinessException {

    public CustomerException(CustomerErrorCode errorCode) {
        super(errorCode);
    }

    public CustomerException(CustomerErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }

    @Override
    public CustomerErrorCode getErrorCode() {
        return (CustomerErrorCode) super.getErrorCode();
    }
}
