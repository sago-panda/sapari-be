package com.sapari.apiapp.controller.seller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.sapari.apiapp.controller.exception.ApiValidationExceptionHandler;
import com.sapari.common.web.response.ErrorResponse;
import com.sapari.global.time.TimeProvider;
import com.sapari.seller.domain.exception.SellerException;

@RestControllerAdvice(basePackages = "com.sapari.apiapp.controller.seller")
public class SellerExceptionHandler extends ApiValidationExceptionHandler {

    public SellerExceptionHandler(TimeProvider timeProvider) {
        super(timeProvider);
    }

    @ExceptionHandler(SellerException.class)
    public ResponseEntity<ErrorResponse> handleSellerException(SellerException exception) {
        return ResponseEntity
                .status(exception.getErrorCode().getStatus())
                .body(createErrorResponse(
                        exception.getErrorCode().getStatus(),
                        exception.getMessage()
                ));
    }
}
