package com.sapari.customer.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mapstruct.factory.Mappers;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import tools.jackson.databind.ObjectMapper;

import com.sapari.customer.application.config.SocialSignupAttemptProperties;
import com.sapari.customer.application.dto.SocialSignupInfo;
import com.sapari.customer.application.mapper.CustomerViewMapper;
import com.sapari.customer.application.port.SocialProfileImageDownloader;
import com.sapari.customer.command.SocialSignupCommand;
import com.sapari.customer.domain.exception.CustomerErrorCode;
import com.sapari.customer.domain.exception.CustomerException;
import com.sapari.customer.domain.repository.SocialLoginCodeRepository;
import com.sapari.customer.domain.repository.SocialSignupRepository;
import com.sapari.customer.infrastructure.redis.SocialSignupAttemptRedisRepository;
import com.sapari.global.time.TimeProvider;
import com.sapari.user.model.ProviderType;
import com.sapari.user.model.UserGender;
import com.sapari.user.port.UserAccountUseCase;
import com.sapari.user.port.UserSignupContactVerificationUseCase;
import com.sapari.user.port.UserSignupEmailVerificationUseCase;
import com.sapari.user.port.UserSignupPhoneVerificationUseCase;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("구매자 소셜 가입 실제 Redis 시도 제어 통합 테스트")
class CustomerAuthServiceRedisAttemptIntegrationTest {

    private static final String SIGNUP_SID = "service-signup-session-id";
    private static final String EMAIL = "customer@example.com";

    private GenericContainer<?> redis;
    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;

    private SocialSignupRepository socialSignupRepository;
    private UserAccountUseCase userAccountUseCase;
    private CustomerJwtTokenAdapter customerJwtTokenAdapter;
    private SocialProfileImageDownloader socialProfileImageDownloader;
    private CustomerAuthService customerAuthService;

    @BeforeAll
    void setUpRedisTemplate() {
        String externalRedisPort = System.getenv("SAPARI_TEST_REDIS_PORT");
        RedisStandaloneConfiguration configuration;
        if (externalRedisPort == null) {
            Assumptions.assumeTrue(
                    DockerClientFactory.instance().isDockerAvailable(),
                    "Docker를 사용할 수 없어 실제 Redis 테스트를 건너뜁니다."
            );
            redis = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
                    .withExposedPorts(6379);
            redis.start();
            configuration = new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379));
        } else {
            configuration = new RedisStandaloneConfiguration("127.0.0.1", Integer.parseInt(externalRedisPort));
        }
        connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    @AfterAll
    void closeRedisConnection() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
        if (redis != null) {
            redis.stop();
        }
    }

    @BeforeEach
    void setUpService() throws Exception {
        try (var connection = connectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
        socialSignupRepository = mock(SocialSignupRepository.class);
        userAccountUseCase = mock(UserAccountUseCase.class);
        customerJwtTokenAdapter = mock(CustomerJwtTokenAdapter.class);
        socialProfileImageDownloader = mock(SocialProfileImageDownloader.class);
        ObjectMapper objectMapper = new ObjectMapper();
        when(socialSignupRepository.findBySid(SIGNUP_SID))
                .thenReturn(Optional.of(objectMapper.writeValueAsString(socialSignupInfo())));

        CustomerSignupContactVerificationAdapter contactVerificationAdapter =
                new CustomerSignupContactVerificationAdapter(
                        mock(UserSignupPhoneVerificationUseCase.class),
                        mock(UserSignupEmailVerificationUseCase.class),
                        mock(UserSignupContactVerificationUseCase.class)
                );
        SocialSignupAttemptRedisRepository realRepository = new SocialSignupAttemptRedisRepository(
                redisTemplate,
                new SocialSignupAttemptProperties(5, Duration.ofMinutes(30), Duration.ofMinutes(2))
        );
        customerAuthService = new CustomerAuthService(
                socialSignupRepository,
                mock(SocialLoginCodeRepository.class),
                userAccountUseCase,
                customerJwtTokenAdapter,
                mock(TimeProvider.class),
                objectMapper,
                Mappers.getMapper(CustomerViewMapper.class),
                contactVerificationAdapter,
                socialProfileImageDownloader,
                realRepository
        );
    }

    @Test
    @DisplayName("실패한 실제 서비스 처리도 Redis quota를 소비하고 6번째 요청은 거절한다")
    void failedServiceOutcomesConsumeRealRedisQuota() {
        when(socialProfileImageDownloader.download(ProviderType.NAVER, "https://image.example/profile.png"))
                .thenReturn(Optional.empty());

        for (int attempt = 1; attempt <= 5; attempt++) {
            assertThatThrownBy(() -> customerAuthService.completeSocialSignup(
                    SIGNUP_SID,
                    signupCommand(true)
            )).isInstanceOfSatisfying(CustomerException.class, exception ->
                    assertThat(exception.getErrorCode()).isEqualTo(CustomerErrorCode.SOCIAL_PROFILE_IMAGE_IMPORT_FAILED)
            );
        }

        assertThatThrownBy(() -> customerAuthService.completeSocialSignup(SIGNUP_SID, signupCommand(true)))
                .isInstanceOfSatisfying(CustomerException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CustomerErrorCode.SOCIAL_SIGNUP_RATE_LIMIT_EXCEEDED)
                );
    }

    private SocialSignupCommand signupCommand(boolean useSocialProfileImage) {
        return new SocialSignupCommand(
                "01012345678",
                EMAIL,
                "customer",
                "구매자",
                LocalDate.of(2000, 1, 1),
                UserGender.FEMALE.name(),
                useSocialProfileImage,
                null,
                null,
                null,
                true,
                true
        );
    }

    private SocialSignupInfo socialSignupInfo() {
        return new SocialSignupInfo(
                ProviderType.NAVER,
                "naver-id",
                "provider@example.com",
                "소셜이름",
                "소셜닉네임",
                "01012345678",
                "https://image.example/profile.png",
                UserGender.MALE,
                LocalDate.of(2000, 1, 1)
        );
    }

}
