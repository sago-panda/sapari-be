package com.sapari.apiapp.controller.exception;

import jakarta.validation.ConstraintViolationException;

import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import com.sapari.common.web.response.ErrorResponse;
import com.sapari.global.time.TimeProvider;

public abstract class ApiValidationExceptionHandler {

    private static final String BAD_REQUEST_MESSAGE = "잘못된 요청입니다.";

    private final TimeProvider timeProvider;

    protected ApiValidationExceptionHandler(TimeProvider timeProvider) {
        this.timeProvider = timeProvider;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValidException(
            MethodArgumentNotValidException exception
    ) {
        String message = exception.getBindingResult()
                .getAllErrors()
                .stream()
                .findFirst()
                .map(MessageSourceResolvable::getDefaultMessage)
                .orElse(BAD_REQUEST_MESSAGE);

        return ResponseEntity
                .badRequest()
                .body(createErrorResponse(HttpStatus.BAD_REQUEST.value(), message));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolationException(
            ConstraintViolationException exception
    ) {
        String message = exception.getConstraintViolations()
                .stream()
                .findFirst()
                .map(violation -> violation.getMessage())
                .orElse(BAD_REQUEST_MESSAGE);

        return ResponseEntity
                .badRequest()
                .body(createErrorResponse(HttpStatus.BAD_REQUEST.value(), message));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleHandlerMethodValidationException(
            HandlerMethodValidationException exception
    ) {
        String message = exception.getParameterValidationResults()
                .stream()
                .flatMap(result -> result.getResolvableErrors().stream())
                .findFirst()
                .map(MessageSourceResolvable::getDefaultMessage)
                .orElse(BAD_REQUEST_MESSAGE);

        return ResponseEntity
                .badRequest()
                .body(createErrorResponse(HttpStatus.BAD_REQUEST.value(), message));
    }

    protected ErrorResponse createErrorResponse(int status, String message) {
        return new ErrorResponse(
                status,
                message,
                timeProvider.now()
        );
    }
}
