package com.sapari.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 모든 REST 응답을 감싸는 공통 봉투. 성공/실패를 같은 골격으로 통일한다.
 *
 * <p>HTTP 상태코드는 {@code @ResponseStatus}/{@code ResponseEntity} 가 책임지고, 이 봉투는
 * body 형식만 담당한다. 성공은 {@code success(data)}, 실패는 예외를 던져 예외 핸들러가
 * {@code fail(error)} 로 감싼다(컨트롤러가 직접 fail 을 만들지 않는다).
 *
 * <p>servlet/webflux 무관한 순수 record 라 양쪽 앱이 공유한다(204 No Content 는 봉투 없이 둔다).
 * 컴포넌트명은 {@code isSuccess} 지만, 정적 팩토리 {@code success()} 와의 이름 충돌을 피하면서
 * JSON 필드는 {@code "success"} 로 노출하기 위해 {@code @JsonProperty} 로 매핑한다.
 *
 * <p>패키지는 {@code com.sapari.common.response} — 표현 DTO 라 도메인 코어가 의존하면 안 된다
 * (ArchUnit 으로 차단). 모듈은 현재 common/core 지만, 패키지명을 모듈과 분리해 두어 나중에
 * 별도 모듈로 추출해도 import 변경이 없게 한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResponseEnvelope<T>(
        @JsonProperty("success") boolean isSuccess,
        T data,
        ErrorResponse error
) {
    public static <T> ResponseEnvelope<T> success(T data) {
        return new ResponseEnvelope<>(true, data, null);
    }

    public static ResponseEnvelope<Void> success() {
        return new ResponseEnvelope<>(true, null, null);
    }

    public static ResponseEnvelope<Void> fail(ErrorResponse error) {
        return new ResponseEnvelope<>(false, null, error);
    }
}
