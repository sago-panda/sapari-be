package com.sapari.liveapp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.bson.UuidRepresentation;
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.sapari.chat.application.port.ChatKickEventPublisher;
import com.sapari.chat.application.service.ChatKickRecorder;
import com.sapari.chat.application.service.KickUserService;
import com.sapari.chat.domain.repository.ChatBanStateRepository;
import com.sapari.chat.domain.repository.ChatBanWriteRepository;
import com.sapari.chat.domain.repository.ChatKickLogRepository;
import com.sapari.chat.domain.repository.ChatKickWriteRepository;
import com.sapari.chat.domain.repository.ChatMessageEvidenceRepository;
import com.sapari.chat.domain.rule.ChatPermissionPolicy;
import com.sapari.chat.infrastructure.persistence.repository.ChatBanJpaRepository;
import com.sapari.chat.infrastructure.persistence.repository.ChatBanStateRepositoryImpl;
import com.sapari.chat.infrastructure.persistence.repository.ChatKickLogJpaRepository;
import com.sapari.chat.infrastructure.persistence.repository.ChatKickLogRepositoryImpl;
import com.sapari.chat.infrastructure.persistence.repository.ChatMessageEvidenceMongoRepository;
import com.sapari.chat.infrastructure.redis.ChatBanWriteRedisRepository;
import com.sapari.chat.infrastructure.redis.ChatKickEventRedisPublisher;
import com.sapari.chat.infrastructure.redis.ChatKickWriteRedisRepository;
import com.sapari.chat.port.KickUserUseCase;
import com.sapari.global.time.TimeProvider;
import com.sapari.live.port.GetLiveRoomUseCase;

/**
 * 강퇴 경로의 <b>블로킹</b> 조각을 이 앱에 세운다.
 *
 * <p>{@code LiveAppApplication}이 {@code com.sapari.chat}을 컴포넌트 스캔에서 통째로 빼므로 chat 쪽 빈은
 * 하나도 자동으로 올라오지 않는다. 그게 의도다 — 같은 모듈에 리액티브 어댑터가 함께 살고, 그것들은 이 앱에
 * 없는 스택을 요구한다. 여기 적힌 것만 살아 있다는 뜻이라, 무엇이 이 앱에 존재하는지가 한눈에 보인다.
 *
 * <p><b>여기 없는 것은 이 앱에 없다.</b> 채팅 전송·팬아웃·세션·레이트리밋은 streaming-app의 몫이고,
 * 이 앱은 강퇴 등록 한 줄기만 갖는다.
 *
 * <p><b>사용자 계정 모듈은 들어오지 않는다.</b> 강퇴에 필요한 역할은 인증 주체와 증거 메시지가 이미 들고
 * 있다. 그걸 계정 저장소에 다시 물으면 이 앱이 {@code UserAccountUseCase}를 갖게 되고, 그 포트에는 탈퇴
 * 신청과 닉네임 변경이 함께 들어 있다 — 방송 앱이 계정을 고칠 수 있게 된다.
 */
@Configuration
public class ChatModerationBeansConfig {

    /**
     * UUID를 standard BSON binary로 읽는다. <b>이 빈이 없으면 증거 조회가 조용히 깨진다.</b>
     *
     * <p>chat이 UUID를 그 표현으로 <b>쓰고</b> 있다(streaming-app의 {@code ChatMongoConfig}가 같은
     * 커스터마이저를 등록한다). 그런데 이 앱은 {@code com.sapari.chat}을 컴포넌트 스캔에서 빼므로 그 설정이
     * 함께 빠진다 — 드라이버 기본값은 {@code UNSPECIFIED}이고, 그러면 standard로 쓰인 UUID가
     * {@code org.bson.types.Binary}로 돌아온다. spring-data-mongodb에 그걸 UUID로 되돌리는 컨버터가 없어
     * 증거 문서 매핑이 실패한다.
     *
     * <p>설정 파일이 아니라 코드로 고정하는 이유도 같다 — {@code application.yaml}은 저장소에 없어서,
     * yml에 맡기면 개발자 환경마다 다르고 클린 배포에서 조용히 빠진다.
     */
    @Bean
    public MongoClientSettingsBuilderCustomizer chatUuidRepresentationCustomizer() {
        return builder -> builder.uuidRepresentation(UuidRepresentation.STANDARD);
    }

    /** 순수 정책이라 프레임워크 애너테이션이 없다 — 그래서 호스트가 직접 세운다. */
    @Bean
    public ChatPermissionPolicy chatPermissionPolicy() {
        return new ChatPermissionPolicy();
    }

    @Bean
    public ChatKickLogRepository chatKickLogRepository(ChatKickLogJpaRepository jpaRepository) {
        return new ChatKickLogRepositoryImpl(jpaRepository);
    }

    @Bean
    public ChatBanStateRepository chatBanStateRepository(ChatBanJpaRepository jpaRepository) {
        return new ChatBanStateRepositoryImpl(jpaRepository);
    }

    /**
     * 증거 원문 조회. 블로킹 {@link MongoTemplate}을 쓰므로 동기 드라이버가 런타임에 있어야 한다
     * (이 앱의 {@code build.gradle}이 넣는다).
     */
    @Bean
    public ChatMessageEvidenceRepository chatMessageEvidenceRepository(MongoTemplate mongoTemplate) {
        return new ChatMessageEvidenceMongoRepository(mongoTemplate);
    }

    @Bean
    public ChatKickWriteRepository chatKickWriteRepository(StringRedisTemplate redisTemplate) {
        return new ChatKickWriteRedisRepository(redisTemplate);
    }

    @Bean
    public ChatBanWriteRepository chatBanWriteRepository(StringRedisTemplate redisTemplate) {
        return new ChatBanWriteRedisRepository(redisTemplate);
    }

    /**
     * 강퇴 이벤트 발행. 받는 쪽은 streaming-app의 팬아웃이고, 두 앱이 같은 Redis를 봐야 전파가 성립한다 —
     * 이 앱의 {@code spring.data.redis} 설정이 streaming-app과 같은 인스턴스를 가리켜야 한다.
     */
    @Bean
    public ChatKickEventPublisher chatKickEventPublisher(StringRedisTemplate redisTemplate) {
        return new ChatKickEventRedisPublisher(redisTemplate);
    }

    /**
     * 강퇴의 DB 쓰기를 감싸는 트랜잭션 빈. {@code @Transactional}이 프록시로 걸리려면 별도 빈이어야 한다 —
     * 같은 클래스 안의 메서드로 두면 자기 호출이라 프록시를 타지 않고, 붙여 놓고 안 걸리는 상태가 된다.
     */
    @Bean
    public ChatKickRecorder chatKickRecorder(ChatKickLogRepository kickLogRepository,
                                             ChatBanStateRepository banStateRepository) {
        return new ChatKickRecorder(kickLogRepository, banStateRepository);
    }

    @Bean
    public KickUserUseCase kickUserUseCase(GetLiveRoomUseCase liveRoomReader,
                                           ChatMessageEvidenceRepository evidenceRepository,
                                           ChatKickRecorder kickRecorder,
                                           ChatBanWriteRepository banWriteRepository,
                                           ChatKickWriteRepository kickWriteRepository,
                                           ChatKickEventPublisher kickEventPublisher,
                                           ChatPermissionPolicy permissionPolicy,
                                           TimeProvider timeProvider) {
        return new KickUserService(liveRoomReader, evidenceRepository, kickRecorder,
                banWriteRepository, kickWriteRepository, kickEventPublisher,
                permissionPolicy, timeProvider);
    }
}
