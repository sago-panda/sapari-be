package com.sapari.user.infrastructure.hash;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.sapari.user.application.config.UserSignupVerificationSecurityProperties;
import com.sapari.user.application.port.VerificationCodeHasher;

/**
 * 회원가입 연락처 인증에 사용하는 전화번호, 이메일, 인증번호를 HMAC-SHA256으로 해시한다.
 * Redis key/value에 원문 연락처와 인증번호가 남지 않도록 모든 저장소 접근 전 동일한 해시 기준을 제공한다.
 */
@Component
public class HmacVerificationCodeHasher implements VerificationCodeHasher {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final String hmacSecret;

    @Autowired
    public HmacVerificationCodeHasher(UserSignupVerificationSecurityProperties properties) {
        this(properties.getHmacSecret());
    }

    public HmacVerificationCodeHasher(String hmacSecret) {
        if (hmacSecret == null || hmacSecret.isBlank()) {
            throw new IllegalArgumentException("hmacSecret must not be blank");
        }
        this.hmacSecret = hmacSecret;
    }

    @Override
    public String hashPhoneNumber(String phoneNumber) {
        return hmac(normalizePhoneNumber(phoneNumber));
    }

    @Override
    public String hashEmail(String email) {
        return hmac(normalizeEmail(email));
    }

    /**
     * 같은 인증번호라도 휴대폰 번호별로 다른 hash가 나오도록 phoneNumber를 함께 넣는다.
     * 한 번호에서 발급된 codeHash가 다른 번호 검증에 재사용되지 않게 하는 격리 정책이다.
     */
    @Override
    public String hashCode(String phoneNumber, String code) {
        return hmac(normalizePhoneNumber(phoneNumber) + ":" + code);
    }

    /**
     * 같은 인증번호라도 이메일별로 다른 hash가 나오도록 normalizedEmail을 함께 넣는다.
     * 한 이메일에서 발급된 codeHash가 다른 이메일 검증에 재사용되지 않게 하는 격리 정책이다.
     */
    @Override
    public String hashEmailCode(String email, String code) {
        return hmac(normalizeEmail(email) + ":" + code);
    }

    /**
     * 사용자 입력과 OAuth provider 입력의 하이픈/공백 차이를 제거해 같은 번호를 같은 key로 다룬다.
     */
    private String normalizePhoneNumber(String phoneNumber) {
        if (phoneNumber == null) {
            return "";
        }
        return phoneNumber.replaceAll("\\D", "");
    }

    /**
     * 이메일은 대소문자/앞뒤 공백 차이로 인증 상태가 분리되지 않도록 소문자와 trim으로 정규화한다.
     */
    private String normalizeEmail(String email) {
        if (email == null) {
            return "";
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String hmac(String value) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(hmacSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
            mac.init(keySpec);
            byte[] digest = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create verification HMAC", e);
        }
    }
}
