package com.sapari.user.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import com.sapari.storage.object.config.S3ObjectStorageProperties;

@DisplayName("프로필 이미지 공개 URL resolver 테스트")
class ProfileImagePublicUrlResolverTest {

    @Test
    @DisplayName("프로필 이미지 storage bean은 enabled 플래그 없이 항상 등록 대상이다")
    void profileImageStorageBeansAreNotConditionalOnEnabledFlag() {
        assertThat(ProfileImagePublicUrlResolver.class.getAnnotation(ConditionalOnProperty.class)).isNull();
        assertThat(ObjectStorageProfileImageStorage.class.getAnnotation(ConditionalOnProperty.class)).isNull();
    }

    @Test
    @DisplayName("public base URL과 object key를 합쳐 프로필 이미지 URL을 만든다")
    void resolveCombinesPublicBaseUrlAndProfileImageKey() {
        ProfileImagePublicUrlResolver resolver = new ProfileImagePublicUrlResolver(properties("http://localhost:9090/sapari-local-assets"));

        String url = resolver.resolve("users/user-id/profile/image.jpg");

        assertThat(url).isEqualTo("http://localhost:9090/sapari-local-assets/users/user-id/profile/image.jpg");
    }

    @Test
    @DisplayName("base URL 뒤 slash와 key 앞 slash가 중복되어도 하나의 slash로 정리한다")
    void resolveNormalizesBoundarySlashes() {
        ProfileImagePublicUrlResolver resolver = new ProfileImagePublicUrlResolver(properties("http://localhost:9090/sapari-local-assets/"));

        String url = resolver.resolve("/users/user-id/profile/image.jpg");

        assertThat(url).isEqualTo("http://localhost:9090/sapari-local-assets/users/user-id/profile/image.jpg");
    }

    @Test
    @DisplayName("프로필 이미지 key가 없으면 URL도 비워 둔다")
    void resolveReturnsNullWhenProfileImageKeyIsBlank() {
        ProfileImagePublicUrlResolver resolver = new ProfileImagePublicUrlResolver(properties("http://localhost:9090/sapari-local-assets"));

        assertThat(resolver.resolve(null)).isNull();
        assertThat(resolver.resolve(" ")).isNull();
    }

    @Test
    @DisplayName("public base URL이 HTTP URL이 아니면 거부한다")
    void resolveRejectsInvalidPublicBaseUrl() {
        ProfileImagePublicUrlResolver resolver = new ProfileImagePublicUrlResolver(properties("ftp://localhost/sapari-local-assets"));

        assertThatThrownBy(() -> resolver.resolve("users/user-id/profile/image.jpg"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("URL");
    }

    @Test
    @DisplayName("DB에 URL 형태 profileImageKey가 들어오면 공개 URL 조합을 거부한다")
    void resolveRejectsUrlShapedProfileImageKey() {
        ProfileImagePublicUrlResolver resolver = new ProfileImagePublicUrlResolver(properties("http://localhost:9090/sapari-local-assets"));

        assertThatThrownBy(() -> resolver.resolve("https://k.kakaocdn.net/profile.png"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("object key");
        assertThatThrownBy(() -> resolver.resolve("http://example.com/profile.png"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("object key");
    }

    private S3ObjectStorageProperties properties(String publicBaseUrl) {
        return new S3ObjectStorageProperties(
                "http://localhost:9090",
                "ap-northeast-2",
                "local-access-key",
                "local-secret-key",
                true,
                "sapari-local-assets",
                publicBaseUrl
        );
    }
}
