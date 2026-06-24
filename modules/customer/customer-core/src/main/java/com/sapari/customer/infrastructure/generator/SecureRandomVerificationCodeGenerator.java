package com.sapari.customer.infrastructure.generator;

import java.security.SecureRandom;

import org.springframework.stereotype.Component;

import com.sapari.customer.application.service.VerificationCodeGenerator;

/**
 * SecureRandom으로 숫자 인증번호를 생성한다.
 * 생성된 숫자가 짧아도 zero-padding으로 고정 길이를 유지해 사용자 입력 자리수를 일관되게 만든다.
 */
@Component
public class SecureRandomVerificationCodeGenerator implements VerificationCodeGenerator {

    private final SecureRandom secureRandom;

    public SecureRandomVerificationCodeGenerator() {
        this(new SecureRandom());
    }

    SecureRandomVerificationCodeGenerator(SecureRandom secureRandom) {
        this.secureRandom = secureRandom;
    }

    /**
     * 요청한 길이만큼 숫자 인증번호를 생성한다.
     * SecureRandom이 반환한 값이 `42`처럼 짧아도 `000042`로 채워 6자리 정책을 보장한다.
     *
     * @throws IllegalArgumentException 인증번호 길이가 0 이하인 경우
     */
    @Override
    public String generateNumericCode(int length) {
        if (length <= 0) {
            throw new IllegalArgumentException("code length must be positive");
        }

        int bound = (int) Math.pow(10, length);
        int code = secureRandom.nextInt(bound);
        return String.format("%0" + length + "d", code);
    }
}
