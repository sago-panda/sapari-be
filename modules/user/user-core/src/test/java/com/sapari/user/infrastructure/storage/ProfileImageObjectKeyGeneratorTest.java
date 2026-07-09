package com.sapari.user.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Constructor;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("프로필 이미지 object key 생성기 테스트")
class ProfileImageObjectKeyGeneratorTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String USER_PREFIX = "users/00000000-0000-0000-0000-000000000001/profile/";

    @Test
    @DisplayName("기존 프로젝트 스타일처럼 UUID 생성을 위한 별도 생성자를 노출하지 않는다")
    void generatorDoesNotExposeUuidGenerationConstructor() {
        assertThat(ProfileImageObjectKeyGenerator.class.getDeclaredConstructors())
                .extracting(Constructor::getParameterCount)
                .containsOnly(0);
    }

    @Test
    @DisplayName("회원별 profile prefix 아래에 이미지 key를 생성한다")
    void generateCreatesUserScopedProfileImageKey() {
        ProfileImageObjectKeyGenerator generator = new ProfileImageObjectKeyGenerator();

        String key = generator.generate(USER_ID, "jpg");

        assertThat(key)
                .startsWith(USER_PREFIX)
                .endsWith(".jpg")
                .matches("users/00000000-0000-0000-0000-000000000001/profile/[0-9a-f\\-]{36}\\.jpg");
    }

    @Test
    @DisplayName("확장자 앞의 dot은 제거하고 소문자로 정규화한다")
    void generateNormalizesExtension() {
        ProfileImageObjectKeyGenerator generator = new ProfileImageObjectKeyGenerator();

        String key = generator.generate(USER_ID, ".PNG");

        assertThat(key).endsWith(".png");
    }

    @Test
    @DisplayName("회원 ID가 없으면 key를 생성하지 않는다")
    void generateRejectsNullUserId() {
        ProfileImageObjectKeyGenerator generator = new ProfileImageObjectKeyGenerator();

        assertThatThrownBy(() -> generator.generate(null, "jpg"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId");
    }

    @Test
    @DisplayName("확장자가 비어 있으면 key를 생성하지 않는다")
    void generateRejectsBlankExtension() {
        ProfileImageObjectKeyGenerator generator = new ProfileImageObjectKeyGenerator();

        assertThatThrownBy(() -> generator.generate(USER_ID, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("extension");
    }

    @Test
    @DisplayName("확장자에 경로 문자가 있으면 key를 생성하지 않는다")
    void generateRejectsPathLikeExtension() {
        ProfileImageObjectKeyGenerator generator = new ProfileImageObjectKeyGenerator();

        assertThatThrownBy(() -> generator.generate(USER_ID, "../jpg"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("확장자");
    }

    @Test
    @DisplayName("프로필 이미지 확장자에는 숫자를 허용하지 않는다")
    void generateRejectsNumericExtensionToken() {
        ProfileImageObjectKeyGenerator generator = new ProfileImageObjectKeyGenerator();

        assertThatThrownBy(() -> generator.generate(USER_ID, "jp2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("확장자");
    }
}
