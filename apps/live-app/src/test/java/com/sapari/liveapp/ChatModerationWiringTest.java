package com.sapari.liveapp;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.Base64;

import org.bson.UuidRepresentation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.ClassUtils;

import com.mongodb.MongoClientSettings;
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import com.sapari.chat.application.service.ChatKickRecorder;
import com.sapari.chat.port.KickUserUseCase;

/**
 * 강퇴 경로가 <b>이 앱에서 실제로 조립되는지</b>를 컨텍스트를 띄워 확인한다.
 *
 * <p>이 테스트는 두 건의 실제 사고에서 나왔다. 둘 다 유닛 테스트가 전부 초록인 채로 운영만 깨졌고,
 * 원인이 같다 — <b>앱이 해야 할 배선을 테스트가 대신 해 주고 있었다.</b>
 *
 * <ul>
 *   <li>DB 쓰기가 {@code @Modifying} 네이티브 INSERT라 트랜잭션 경계를 요구하는데 아무도 열지 않았다.
 *       서비스 테스트는 목이라 쿼리가 돌지 않았고, 리포지토리 테스트는 {@code @DataJpaTest}가 경계를
 *       대신 열어 줬다. 운영에서는 인가를 통과한 뒤 500이었다.</li>
 *   <li>UUID를 standard BSON binary로 읽는 설정이 chat 쪽 {@code @Configuration}에 있는데, 이 앱이 chat을
 *       스캔에서 빼면서 함께 빠졌다. 쓰는 쪽은 정상이라 데이터는 멀쩡한데 읽는 쪽만 깨졌다.</li>
 * </ul>
 *
 * <p>그래서 여기서는 <b>컨텍스트가 실제로 만든 빈</b>을 본다. 손으로 조립해 검사하면 같은 구멍이 다시 생긴다.
 *
 * <p>설정은 이 테스트가 직접 채운다. {@code application.yaml}이 저장소에 없어 클린 체크아웃에서는 어차피
 * 없고, 그걸 이유로 태그를 달아 CI에서 빼면 <b>이 테스트가 막으려는 회귀를 CI가 영원히 못 잡는다.</b>
 * DB·Mongo·Redis에 실제로 붙지는 않는다 — 클라이언트는 지연 연결이고 JPA 메타데이터 조회도 꺼 둔다.
 */
@SpringBootTest
@DisplayName("강퇴 배선 — 앱이 조립해야 할 것을 테스트가 대신하지 않는다")
class ChatModerationWiringTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5432/sapari_test");
        registry.add("spring.datasource.username", () -> "test");
        registry.add("spring.datasource.password", () -> "test");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        // 이게 없으면 방언을 지정해도 Hibernate가 부팅 중 메타데이터를 읽으려 커넥션을 연다.
        registry.add("spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access", () -> false);

        registry.add("jwt.issuer", () -> "sapari-test");
        registry.add("jwt.secret", () -> "test-only-secret-not-a-credential-32chars");
        registry.add("jwt.access-token-expiration-seconds", () -> 3600);
        registry.add("jwt.refresh-token-expiration-seconds", () -> 1209600);

        registry.add("live.room-token.issuer", () -> "live");
        registry.add("live.room-token.audience", () -> "chat");
        registry.add("live.room-token.expiration-seconds", () -> 90);
        registry.add("live.room-token.private-key", ChatModerationWiringTest::generatedPrivateKey);

        registry.add("livekit.host", () -> "http://localhost:7880");
        registry.add("livekit.api-key", () -> "test-key");
        registry.add("livekit.api-secret", () -> "test-secret");
        registry.add("livekit.s3.bucket", () -> "test-bucket");
        registry.add("livekit.s3.region", () -> "ap-northeast-2");
        registry.add("livekit.s3.key-prefix", () -> "test/");
        registry.add("livekit.s3.access-key", () -> "test-access");
        registry.add("livekit.s3.secret-key", () -> "test-secret");
        registry.add("livekit.hls.cdn-base-url", () -> "http://localhost/hls");
        registry.add("livekit.hls.segment-duration", () -> 4);

        registry.add("management.server.port", () -> 0);

        // ⚠️ 재조정 잡 셋을 끈다. 마스터 스위치가 matchIfMissing=true라 설정이 없으면 <b>켜진다</b> —
        //    저장소에 application.yaml이 없으니 이 테스트에서는 부재가 곧 활성이다. 그대로 두면
        //    컨텍스트가 크론과 함께 뜨고, 컨텍스트는 테스트 JVM 수명 동안 캐시되므로 10분 경계를 넘기면
        //    실제로 발화한다. 그중 둘은 방송을 시작시키거나 켜진 방송을 끝낼 수 있다.
        //    지금 안 터지는 건 자격증명이 가짜라서지 설계된 차단이 아니다.
        registry.add("live.reconcile.enabled", () -> false);
    }

    /**
     * 룸 토큰 개인키는 실행할 때마다 만든다 — 저장소에 개인키 모양의 문자열을 남기지 않기 위해서다.
     * 이 테스트는 서명 결과를 검증하지 않으므로 값이 무엇이든 형식만 맞으면 된다.
     */
    private static String generatedPrivateKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return Base64.getEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException("테스트용 룸 토큰 키 생성 실패", e);
        }
    }

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("강퇴 유스케이스가 조립된다 — 빠진 협력자가 있으면 부팅이 실패한다")
    void kickUseCaseIsAssembled() {
        assertThat(context.getBean(KickUserUseCase.class)).isNotNull();
    }

    @Test
    @DisplayName("⭐ 기록 빈이 프록시다 — 프록시가 아니면 @Transactional이 안 걸리고 모든 강퇴가 500이 된다")
    void recorderIsATransactionalProxy() {
        // given: @Transactional은 프록시를 통해서만 걸린다. 같은 클래스 안의 메서드로 옮기거나
        //        빈 등록을 잃으면 애너테이션은 남은 채 효력만 사라진다 — 그때 조용해진다.
        ChatKickRecorder recorder = context.getBean(ChatKickRecorder.class);

        // when & then
        assertThat(AopUtils.isAopProxy(recorder))
                .as("ChatKickRecorder가 프록시가 아니다 — @Transactional이 걸리지 않는다")
                .isTrue();
    }

    /**
     * ⭐ UUID 표현이 standard로 적용되는지.
     *
     * <p><b>{@code MongoClientSettings} 빈을 그대로 보면 안 된다</b> — 그 빈은 프로퍼티에서만 만들어지고
     * 커스터마이저는 클라이언트를 만들 때 적용된다(실측: 그 빈은 {@code UNSPECIFIED}다). 그래서 여기서는
     * 그 조립을 그대로 재현한다 — 등록된 커스터마이저 전부를 빌더에 얹고 결과를 본다. 커스터마이저가
     * 사라지거나 다른 값을 세우면 여기서 깨진다.
     */
    @Test
    @DisplayName("⭐ UUID를 standard로 읽는다 — 기본값이면 증거 문서의 UUID가 Binary로 돌아온다")
    void uuidRepresentationIsStandard() {
        // given: 쓰는 쪽(streaming-app)이 standard로 쓴다. 이 앱이 chat 설정을 스캔에서 빼므로
        //        같은 값을 여기서 다시 세우지 않으면 읽기만 조용히 깨진다.
        // ⚠️ 순서가 중요하다. Boot 자신의 커스터마이저(@Order(0))가 프로퍼티 값으로 이 필드를 덮으므로,
        //    정렬하지 않고 적용하면 우리 값이 지워진 결과가 나온다(실측: UNSPECIFIED). 실제 조립과 같게
        //    @Order로 정렬해야 우리 커스터마이저가 마지막에 온다.
        List<MongoClientSettingsBuilderCustomizer> customizers =
                new ArrayList<>(context.getBeansOfType(MongoClientSettingsBuilderCustomizer.class).values());
        AnnotationAwareOrderComparator.sort(customizers);

        MongoClientSettings.Builder builder = MongoClientSettings.builder();
        customizers.forEach(customizer -> customizer.customize(builder));

        // when & then
        assertThat(builder.build().getUuidRepresentation()).isEqualTo(UuidRepresentation.STANDARD);
    }

    /**
     * 이 앱에 존재하는 chat 빈의 <b>전부</b>를 고정한다.
     *
     * <p>패키지 몇 개만 골라 보면 검사가 이름이 주장하는 것보다 좁아진다 — 걸러 낸 자리 밖에서 새면
     * 초록인 채로 통과한다. 그래서 특정 패키지가 아니라 {@code com.sapari.chat} 전체를 세고, 이 앱이
     * <b>일부러 등록한 것</b>과 정확히 같은지 본다.
     *
     * <p>목록에 없는 것이 하나라도 생기면 깨진다. 그게 의도다 — chat 빈이 이 앱에 새로 생기는 일은
     * 스캔 제외가 풀렸거나 누군가 {@code @Bean}을 더한 것이고, 둘 다 사람이 한 번 봐야 하는 변화다.
     */
    @Test
    @DisplayName("⭐ 이 앱의 chat 빈은 일부러 등록한 것뿐이다 — 하나라도 더 있으면 스캔이 새고 있다")
    void onlyDeliberatelyRegisteredChatBeansExist() {
        // given: 리액티브 어댑터가 @Repository를 달고 같은 모듈에 살고, 그중 브로드캐스터는
        //        생성자에서 Redis에 접속한다. 새어 들어오면 이 앱이 채팅 팬아웃 노드가 된다.
        Set<String> expected = Set.of(
                // ChatModerationBeansConfig 가 세우는 것
                "ChatPermissionPolicy", "ChatKickRecorder", "KickUserService",
                "ChatKickLogRepositoryImpl", "ChatBanStateRepositoryImpl",
                "ChatMessageEvidenceMongoRepository",
                "ChatKickWriteRedisRepository", "ChatBanWriteRedisRepository",
                "ChatKickEventRedisPublisher");

        // when
        Set<String> actual = Arrays.stream(context.getBeanDefinitionNames())
                .map(context::getType)
                .filter(type -> type != null && type.getName().startsWith("com.sapari.chat."))
                // Spring Data가 만드는 리포지토리 프록시는 인터페이스 이름으로 잡힌다 — 이 앱이
                // @EnableJpaRepositories로 명시한 것이라 목록에 넣지 않고 여기서 걷어낸다.
                .filter(type -> !type.isInterface())
                // 프록시가 씌워진 빈은 이름이 ChatKickRecorder$$SpringCGLIB$$0 꼴이 된다.
                // 원본 클래스로 되돌려 비교한다 — 프록시 유무는 이 테스트가 볼 것이 아니다(별도 테스트).
                .map(type -> ClassUtils.getUserClass(type).getSimpleName())
                .collect(Collectors.toSet());

        // then
        assertThat(actual)
                .as("이 앱에 있어야 할 chat 빈과 실제가 다르다 — 스캔 제외가 풀렸거나 등록이 늘었다")
                .isEqualTo(expected);
    }
}
