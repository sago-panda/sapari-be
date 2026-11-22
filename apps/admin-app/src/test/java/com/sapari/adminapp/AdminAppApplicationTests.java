package com.sapari.adminapp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.sapari.common.securityjwt.jwt.JwtTestKeys;

/**
 * 이 앱이 실제로 뜨는지만 본다 — 가장 싼 부팅 가드다.
 *
 * <p><b>CI 에서도 돈다.</b> 종전에는 {@code @Tag("context")} 로 제외돼 있었는데, 이 앱이 요구하는 것은
 * {@code common:web} 이 끌고 오는 JWT 프로퍼티뿐이다(모듈 의존 0건인 껍데기) — 외부 인프라도
 * 자격증명도 없다. 즉 제외할 이유가 애초에 없었고, 태그가 세 앱에 일괄로 붙어 있었을 뿐이다.
 *
 * <p>껍데기라도 이 가드가 비어 있으면 안 되는 이유: 이 앱이 채워지기 시작할 때 <b>첫 부팅 실패를
 * 파이프라인이 잡아 준다</b>. 지금 통과한다는 사실이 그 시작점이다.
 */
@SpringBootTest
@DisplayName("admin-app — 컨텍스트가 뜬다")
class AdminAppApplicationTests {

    /**
     * {@code common:web} 의 JWT 프로퍼티. 검증 공개키는 실제로 파싱되는 RSA 키여야 한다 — 그 규칙을
     * 비껴가려 검증을 끄면 이 테스트가 운영과 다른 구성을 재게 된다.
     */
    @DynamicPropertySource
    static void requiredConfig(DynamicPropertyRegistry registry) {
        registry.add("jwt.issuer", () -> "sapari-test");
        registry.add("jwt.verification-keys[0].kid", () -> JwtTestKeys.KID);
        registry.add("jwt.verification-keys[0].public-key", JwtTestKeys::publicKeyBase64);
        registry.add("jwt.access-token-expiration-seconds", () -> "1800");
        registry.add("jwt.refresh-token-expiration-seconds", () -> "604800");
    }

    @Test
    @DisplayName("컨텍스트가 로드된다")
    void contextLoads() {
    }
}
