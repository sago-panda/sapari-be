package com.sapari.common.web.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import com.sapari.common.response.ResponseEnvelope;
import com.sapari.global.time.TimeProvider;

@DisplayName("전역 multipart 예외 응답 테스트")
class GlobalExceptionHandlerMultipartTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(
            new TimeProvider(Clock.fixed(Instant.parse("2026-07-10T00:00:00Z"), ZoneOffset.UTC))
    );
    private final ServletWebRequest request = new ServletWebRequest(new MockHttpServletRequest());

    @Test
    @DisplayName("필수 multipart part 누락은 COMMON 실패 봉투의 400 응답이다")
    void missingMultipartPartReturnsBadRequestEnvelope() throws Exception {
        ResponseEntity<Object> response = handler.handleException(
                new MissingServletRequestPartException("request"),
                request
        );

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertCommonFailureEnvelope(response);
    }

    @Test
    @DisplayName("multipart 업로드 제한 초과는 COMMON 실패 봉투의 413 응답이다")
    void oversizedMultipartReturnsPayloadTooLargeEnvelope() throws Exception {
        ResponseEntity<Object> response = handler.handleException(
                new MaxUploadSizeExceededException(1024L),
                request
        );

        assertThat(response.getStatusCode().value()).isEqualTo(413);
        assertCommonFailureEnvelope(response);
    }

    private void assertCommonFailureEnvelope(ResponseEntity<Object> response) {
        assertThat(response.getBody()).isInstanceOfSatisfying(ResponseEnvelope.class, envelope -> {
            assertThat(envelope.isSuccess()).isFalse();
            assertThat(envelope.data()).isNull();
            assertThat(envelope.error()).isNotNull();
            assertThat(envelope.error().code()).isEqualTo("COMMON-001");
        });
    }
}
