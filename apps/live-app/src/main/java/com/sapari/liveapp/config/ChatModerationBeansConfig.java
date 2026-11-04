package com.sapari.liveapp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.bson.UuidRepresentation;
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.sapari.chat.application.port.ChatKickEventPublisher;
import com.sapari.chat.application.service.ChatBanReleaseRecorder;
import com.sapari.chat.application.service.ChatKickRecorder;
import com.sapari.chat.application.service.GetChatHistoryService;
import com.sapari.chat.application.service.KickUserService;
import com.sapari.chat.application.service.ReconcileKickListService;
import com.sapari.chat.application.service.ReleaseBanService;
import com.sapari.chat.domain.repository.ChatHistoryRepository;
import com.sapari.chat.infrastructure.persistence.mapper.ChatMessageMapper;
import com.sapari.chat.infrastructure.persistence.mapper.ChatMessageMapperImpl;
import com.sapari.chat.infrastructure.persistence.repository.ChatHistoryMongoRepository;
import com.sapari.chat.port.GetChatHistoryUseCase;
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
import com.sapari.chat.application.port.ChatAccountEventPublisher;
import com.sapari.chat.infrastructure.redis.ChatAccountEventRedisPublisher;
import com.sapari.chat.infrastructure.redis.ChatKickEventRedisPublisher;
import com.sapari.chat.infrastructure.redis.ChatKickWriteRedisRepository;
import com.sapari.chat.port.KickUserUseCase;
import com.sapari.chat.port.ReconcileKickListUseCase;
import com.sapari.chat.port.ReleaseBanUseCase;
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
     * <p><b>이름이 chat 쪽 빈과 달라야 한다.</b> 같은 이름이면 언젠가 스캔 제외가 풀렸을 때 정의가
     * 겹쳐 부팅이 죽는다(빈 오버라이드는 기본이 꺼져 있다). 같은 일을 하는 두 빈이라 겹쳐도
     * 무해할 것 같지만, 무해한 쪽은 중복 등록이 아니라 부팅 실패다.
     *
     * <p>설정 파일이 아니라 코드로 고정하는 이유도 같다 — {@code application.yaml}은 저장소에 없어서,
     * yml에 맡기면 개발자 환경마다 다르고 클린 배포에서 조용히 빠진다.
     */
    @Bean
    public MongoClientSettingsBuilderCustomizer liveAppChatUuidRepresentationCustomizer() {
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

    /**
     * 문서 ↔ 도메인 매퍼.
     *
     * <p>생성된 구현을 손으로 세운다 — {@code componentModel = "spring"} 이라 그 구현에
     * {@code @Component} 가 붙지만 이 앱은 {@code com.sapari.chat} 을 스캔하지 않는다. 리액티브 쪽은
     * 스캔으로 같은 빈을 얻으므로 <b>구현이 하나이고 쓰는 쪽이 둘</b>이다 — 손수 필드를 옮기는 두 번째
     * 변환을 만들지 않는 것이 요점이고, 그 매퍼가 {@code unmappedTargetPolicy = ERROR} 라 도메인에
     * 필드가 늘면 빌드가 막는다.
     */
    @Bean
    public ChatMessageMapper chatMessageMapper() {
        return new ChatMessageMapperImpl();
    }

    /** 이력 읽기. 증거 조회와 같은 이유로 블로킹이다 — 호출자가 MVC다. */
    @Bean
    public ChatHistoryRepository chatHistoryRepository(MongoTemplate mongoTemplate,
                                                       ChatMessageMapper chatMessageMapper) {
        return new ChatHistoryMongoRepository(mongoTemplate, chatMessageMapper);
    }

    /**
     * 이력 조회 유스케이스.
     *
     * <p>{@code GetLiveRoomUseCase} 를 받는다 — 특권 뷰 판정에 방 주인이 필요하고, VOD 창을 역산하는
     * 기준이 방송 시작 시각이다. 둘 다 live 소유 값이라 포트로만 묻는다.
     */
    @Bean
    public GetChatHistoryUseCase getChatHistoryUseCase(GetLiveRoomUseCase liveRoomReader,
                                                        ChatHistoryRepository historyRepository,
                                                        ChatKickLogRepository kickLogRepository,
                                                        ChatPermissionPolicy permissionPolicy) {
        return new GetChatHistoryService(liveRoomReader, historyRepository, kickLogRepository,
                permissionPolicy);
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
     * 계정 조치 발행. 강퇴 발행과 채널이 다르다 — 강퇴는 방 채널이라 그 방 세션만 닿고, 밴은 계정 전체라
     * 방을 모르는 채로 모든 Pod에 닿아야 한다. 받는 쪽은 streaming-app의 {@code ChatAccountEventHandler}다.
     */
    @Bean
    public ChatAccountEventPublisher chatAccountEventPublisher(StringRedisTemplate redisTemplate) {
        return new ChatAccountEventRedisPublisher(redisTemplate);
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
                                           ChatAccountEventPublisher accountEventPublisher,
                                           ChatPermissionPolicy permissionPolicy,
                                           TimeProvider timeProvider) {
        return new KickUserService(liveRoomReader, evidenceRepository, kickRecorder,
                banWriteRepository, kickWriteRepository, kickEventPublisher,
                accountEventPublisher, permissionPolicy, timeProvider);
    }

    /**
     * 밴 해제의 DB 쓰기를 감싸는 트랜잭션 빈 — 위 {@code chatKickRecorder}와 같은 이유로 별도 빈이다.
     */
    @Bean
    public ChatBanReleaseRecorder chatBanReleaseRecorder(ChatBanStateRepository banStateRepository) {
        return new ChatBanReleaseRecorder(banStateRepository);
    }

    /**
     * 밴 해제. 방을 읽지 않으므로 {@code GetLiveRoomUseCase}를 받지 않는다 — 밴은 사람에 붙고
     * 해제는 ADMIN 전용이라, 어느 방에서 눌렀는지가 판정을 바꾸지 않는다.
     *
     * <p>발행도 하지 않는다. 밴이 <b>걸릴</b> 때는 당사자 세션을 끊어야 해서 계정 채널로 발행하지만
     * (위 {@code chatAccountEventPublisher}), 풀릴 때 끊을 세션이 없다 — 막혀 있던 사람은 애초에
     * 접속하지 못했고, 다음 접속에서 입장 게이트가 미러를 다시 읽는다.
     */
    @Bean
    public ReleaseBanUseCase releaseBanUseCase(ChatPermissionPolicy permissionPolicy,
                                               ChatBanReleaseRecorder releaseRecorder,
                                               ChatBanWriteRepository banWriteRepository,
                                               TimeProvider timeProvider) {
        return new ReleaseBanService(permissionPolicy, releaseRecorder, banWriteRepository, timeProvider);
    }

    /**
     * 강퇴 명단 재적재. 트랜잭션 빈이 없다 — 정본은 읽기만 하고 쓰기는 Redis 한 번이라
     * {@code @Modifying} 경계가 필요하지 않다.
     */
    @Bean
    public ReconcileKickListUseCase reconcileKickListUseCase(ChatPermissionPolicy permissionPolicy,
                                                             ChatKickLogRepository kickLogRepository,
                                                             ChatKickWriteRepository kickWriteRepository) {
        return new ReconcileKickListService(permissionPolicy, kickLogRepository, kickWriteRepository);
    }
}
