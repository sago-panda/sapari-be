package com.sapari.user.infrastructure.generator;

import java.security.SecureRandom;

import org.springframework.stereotype.Component;

import com.sapari.user.application.port.VerificationCodeGenerator;

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

    @Override
    public String generateNumericCode(int length) {
        // 현재 숫자 OTP는 SecureRandom.nextInt(int bound)를 사용하므로 10^length가 int 범위 안인 길이만 허용한다.
        if (length < 1 || length > 9) {
            throw new IllegalArgumentException("code length must be between 1 and 9");
        }

        int bound = (int) Math.pow(10, length);
        int code = secureRandom.nextInt(bound);
        return String.format("%0" + length + "d", code);
    }
}
