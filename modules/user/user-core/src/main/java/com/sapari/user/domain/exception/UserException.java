package com.sapari.user.domain.exception;

import com.sapari.common.core.exception.BusinessException;

public class UserException extends BusinessException {

    public UserException(UserErrorCode errorCode) {
        super(errorCode);
    }

    public UserException(UserErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }
}
