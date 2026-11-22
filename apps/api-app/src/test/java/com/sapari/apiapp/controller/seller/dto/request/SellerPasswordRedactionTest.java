package com.sapari.apiapp.controller.seller.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class SellerPasswordRedactionTest {

    /** 요청 DTO와 command의 문자열 출력에 두 비밀번호 원문이 남지 않는지 검증한다. */
    @ParameterizedTest
    @MethodSource("credentialObjects")
    void excludesPasswords(Object credentials) {
        assertThat(credentials.toString()).doesNotContain("SecretLogin1!", "SecretConfirm2!");
    }

    /** 실제 요청 변환을 통해 로그인·가입의 입력/command 경계를 모두 검사한다. */
    static Stream<Object> credentialObjects() {
        SellerLoginRequest login = new SellerLoginRequest("seller@example.com", "SecretLogin1!");
        SellerSignupRequest signup = new SellerSignupRequest(
                "seller@example.com", "SecretLogin1!", "SecretConfirm2!", "seller", "판매자",
                "01012345678", true, false, "상점", "1234567890",
                LocalDate.of(2020, 1, 1), "INDIVIDUAL");
        return Stream.of(login, login.toCommand(), signup, signup.toCommand());
    }
}
