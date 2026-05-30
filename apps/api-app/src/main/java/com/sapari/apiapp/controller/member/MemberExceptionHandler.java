package com.sapari.apiapp.controller.member;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.sapari.apiapp.controller.exception.ApiValidationExceptionHandler;
import com.sapari.common.web.response.ErrorResponse;
import com.sapari.global.time.TimeProvider;
import com.sapari.member.domain.exception.MemberException;

@RestControllerAdvice(basePackages = "com.sapari.apiapp.controller.member")
public class MemberExceptionHandler extends ApiValidationExceptionHandler {

    public MemberExceptionHandler(TimeProvider timeProvider) {
        super(timeProvider);
    }

    @ExceptionHandler(MemberException.class)
    public ResponseEntity<ErrorResponse> handleMemberException(MemberException exception) {
        return ResponseEntity
                .status(exception.getErrorCode().getStatus())
                .body(createErrorResponse(
                        exception.getErrorCode().getStatus(),
                        exception.getMessage()
                ));
    }
}
