package com.sapari.user.infrastructure.storage;

import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

/**
 * 프로필 이미지 object key를 서버 정책으로 생성한다.
 * 클라이언트가 key를 지정하지 못하게 해 다른 회원 경로 덮어쓰기와 경로 조작을 차단한다.
 */
@Component
public class ProfileImageObjectKeyGenerator {

    private static final String PROFILE_IMAGE_KEY_PREFIX = "users";

    /**
     * 프로필 이미지 prefix 아래에 회원별 UUID 기반 object key를 만든다.
     * key를 서버가 생성해야 사용자 입력으로 다른 회원 경로를 덮어쓰거나 경로 조작을 시도하는 흐름을 막을 수 있다.
     */
    public String generate(UUID userId, String extension) {
        Assert.notNull(userId, "userId는 필수입니다.");
        String normalizedExtension = normalizeExtension(extension);
        return "%s/%s/profile/%s.%s".formatted(PROFILE_IMAGE_KEY_PREFIX, userId, UUID.randomUUID(), normalizedExtension);
    }

    /**
     * 저장 key에 들어갈 확장자를 영문 소문자 토큰으로 정규화한다.
     * 확장자 allowlist 검증은 전용 파일 검증 컴포넌트가 담당하고,
     * 여기서는 마지막 방어선으로 숫자, 경로 문자, 복합 확장자, 특수문자 주입을 차단한다.
     */
    private String normalizeExtension(String extension) {
        Assert.hasText(extension, "extension은 필수입니다.");
        String normalized = extension.startsWith(".") ? extension.substring(1) : extension;
        normalized = normalized.toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z]+")) {
            // allowlist 검증 이후에도 숫자·경로 문자·복합 확장자 주입은 key 생성 직전에 한 번 더 막는다.
            throw new IllegalArgumentException("지원하지 않는 이미지 확장자입니다.");
        }
        return normalized;
    }
}
