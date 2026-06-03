package com.sapari.member.domain.exception;

import com.sapari.common.core.exception.BusinessException;

public class MemberException extends BusinessException {

    public MemberException(MemberErrorCode errorCode) {
        super(errorCode);
    }

    public MemberException(MemberErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }

    @Override
    public MemberErrorCode getErrorCode() {
        return (MemberErrorCode) super.getErrorCode();
    }
}
