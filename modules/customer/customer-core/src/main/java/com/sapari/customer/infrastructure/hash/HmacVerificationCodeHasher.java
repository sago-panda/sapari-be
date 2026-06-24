package com.sapari.customer.infrastructure.hash;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.sapari.customer.application.config.CustomerPhoneVerificationProperties;
import com.sapari.customer.application.service.VerificationCodeHasher;

/**
 * 구매자 휴대폰 인증에 사용하는 전화번호와 인증번호를 HMAC-SHA256으로 해시한다.
 * Redis key/value에 원문 전화번호와 인증번호가 남지 않도록 모든 저장소 접근 전 동일한 해시 기준을 제공한다.
 */
@Component
public class HmacVerificationCodeHasher implements VerificationCodeHasher {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final String hmacSecret;

    @Autowired
    public HmacVerificationCodeHasher(CustomerPhoneVerificationProperties properties) {
        this(properties.getHmacSecret());
    }

    public HmacVerificationCodeHasher(String hmacSecret) {
        if (hmacSecret == null || hmacSecret.isBlank()) {
            throw new IllegalArgumentException("hmacSecret must not be blank");
        }
        this.hmacSecret = hmacSecret;
    }

    /**
     * 정규화된 전화번호를 해시해 Redis key에 사용할 phoneHash를 만든다.
     * 전화번호 원문을 key에 쓰지 않아 Redis key 조회만으로 개인정보가 노출되는 일을 줄인다.
     */
    @Override
    public String hashPhoneNumber(String phoneNumber) {
        return hmac(normalizePhoneNumber(phoneNumber));
    }

    /**
     * 정규화된 전화번호와 인증번호를 함께 해시해 Redis value에 저장할 codeHash를 만든다.
     * 같은 인증번호라도 다른 전화번호에서는 재사용되지 않도록 전화번호를 입력값에 포함한다.
     */
    @Override
    public String hashCode(String phoneNumber, String code) {
        return hmac(normalizePhoneNumber(phoneNumber) + ":" + code);
    }

    /**
     * 발송/확인/회원가입 검증에서 같은 번호 기준을 쓰도록 숫자만 남긴다.
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
