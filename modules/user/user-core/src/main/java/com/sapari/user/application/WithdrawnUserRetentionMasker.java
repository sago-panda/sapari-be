package com.sapari.user.application;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 탈퇴회원 보존 테이블에는 원문 식별정보를 저장하지 않기 때문에
 * 사용자 식별 힌트로 필요한 최소 범위만 남기도록 마스킹한다.
 */
@Component
public class WithdrawnUserRetentionMasker {

    public String maskName(String name) {
        if (!StringUtils.hasText(name)) {
            return null;
        }

        String trimmedName = name.trim();
        int length = trimmedName.length();
        if (length == 1) {
            return "*";
        }
        if (length == 2) {
            return trimmedName.charAt(0) + "*";
        }

        return trimmedName.charAt(0)
                + "*".repeat(length - 2)
                + trimmedName.charAt(length - 1);
    }

    public String maskEmail(String email) {
        if (!StringUtils.hasText(email)) {
            return null;
        }

        String trimmedEmail = email.trim();
        int atIndex = trimmedEmail.indexOf('@');
        if (atIndex < 0) {
            return maskEmailLocalPart(trimmedEmail);
        }

        String localPart = trimmedEmail.substring(0, atIndex);
        String domain = trimmedEmail.substring(atIndex);
        return maskEmailLocalPart(localPart) + domain;
    }

    public String maskPhoneNumber(String phoneNumber) {
        if (!StringUtils.hasText(phoneNumber)) {
            return null;
        }

        String trimmedPhoneNumber = phoneNumber.trim();
        if (trimmedPhoneNumber.length() < 8) {
            return "*".repeat(trimmedPhoneNumber.length());
        }

        return trimmedPhoneNumber.substring(0, 3)
                + "****"
                + trimmedPhoneNumber.substring(trimmedPhoneNumber.length() - 4);
    }

    private String maskEmailLocalPart(String localPart) {
        if (localPart.length() <= 1) {
            return localPart + "***";
        }

        return localPart.substring(0, Math.min(2, localPart.length())) + "***";
    }
}
