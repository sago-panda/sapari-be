package com.sapari.user.infrastructure.hash;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.sapari.user.application.config.UserSignupPhoneVerificationProperties;
import com.sapari.user.application.port.VerificationCodeHasher;

/**
 * 회원가입 휴대폰 인증에 사용하는 전화번호와 인증번호를 HMAC-SHA256으로 해시한다.
 * Redis key/value에 원문 전화번호와 인증번호가 남지 않도록 모든 저장소 접근 전 동일한 해시 기준을 제공한다.
 */
@Component
public class HmacVerificationCodeHasher implements VerificationCodeHasher {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final String hmacSecret;

    @Autowired
    public HmacVerificationCodeHasher(UserSignupPhoneVerificationProperties properties) {
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

    /**
     * 같은 인증번호라도 휴대폰 번호별로 다른 hash가 나오도록 phoneNumber를 함께 넣는다.
     * 한 번호에서 발급된 codeHash가 다른 번호 검증에 재사용되지 않게 하는 격리 정책이다.
     */
    @Override
    public String hashCode(String phoneNumber, String code) {
        return hmac(normalizePhoneNumber(phoneNumber) + ":" + code);
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
