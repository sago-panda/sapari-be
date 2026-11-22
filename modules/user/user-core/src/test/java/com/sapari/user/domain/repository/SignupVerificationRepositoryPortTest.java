package com.sapari.user.domain.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("회원가입 인증 repository port 계약 테스트")
class SignupVerificationRepositoryPortTest {

    @Test
    @DisplayName("휴대폰 인증 repository port는 비원자 confirm 재료 메서드를 공개하지 않는다")
    void phoneRepositoryPortDoesNotExposeNonAtomicConfirmSteps() {
        assertThat(methodNames(SignupPhoneVerificationRepository.class))
                .doesNotContain("findCodeHash", "incrementFailure", "deleteCodeAndFailures", "saveVerified");
    }

    @Test
    @DisplayName("이메일 인증 repository port는 비원자 confirm 재료 메서드를 공개하지 않는다")
    void emailRepositoryPortDoesNotExposeNonAtomicConfirmSteps() {
        assertThat(methodNames(SignupEmailVerificationRepository.class))
                .doesNotContain("findCodeHash", "incrementFailure", "deleteCodeAndFailures", "saveVerified");
    }

    private Set<String> methodNames(Class<?> repositoryType) {
        return Arrays.stream(repositoryType.getMethods())
                .map(method -> method.getName())
                .collect(Collectors.toSet());
    }
}
