package com.sapari.liveapp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.bson.UuidRepresentation;
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.sapari.chat.application.port.ChatKickEventPublisher;
import com.sapari.chat.application.port.ProfanityDictionarySnapshotPublisher;
import com.sapari.chat.application.port.ProfanityFilterFactory;
import com.sapari.chat.application.service.ChatAuditRecorder;
import com.sapari.chat.application.service.ChatFilterWordWriteRecorder;
import com.sapari.chat.application.service.ManageFilterWordsService;
import com.sapari.chat.infrastructure.config.DictionaryProfanityFilterFactory;
import com.sapari.chat.port.ManageFilterWordsUseCase;
import com.sapari.chat.application.service.ChatFilterWordSeedRecorder;
import com.sapari.chat.application.service.ProfanityDictionaryPublishService;
import com.sapari.chat.domain.repository.ChatAuditLogRepository;
import com.sapari.chat.domain.repository.ChatFilterWordRepository;
import com.sapari.chat.infrastructure.persistence.repository.ChatAuditLogJpaRepository;
import com.sapari.chat.infrastructure.persistence.repository.ChatAuditLogRepositoryImpl;
import com.sapari.chat.infrastructure.persistence.repository.ChatFilterWordJpaRepository;
import com.sapari.chat.infrastructure.persistence.repository.ChatFilterWordRepositoryImpl;
import com.sapari.chat.infrastructure.redis.ProfanityDictionarySnapshotRedisPublisher;
import com.sapari.chat.application.service.ChatBanReleaseRecorder;
import com.sapari.chat.application.service.ChatKickRecorder;
import com.sapari.chat.application.service.GetChatHistoryService;
import com.sapari.chat.application.service.KickUserService;
import com.sapari.chat.application.service.ClearRoomEndedMarkerService;
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
import com.sapari.chat.domain.repository.ChatRoomEndedWriteRepository;
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
import com.sapari.chat.infrastructure.redis.ChatRoomEndedWriteRedisRepository;
import com.sapari.chat.port.KickUserUseCase;
import com.sapari.chat.port.ClearRoomEndedMarkerUseCase;
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
     * 감사 기록 쓰기. 들어가는 테이블은 {@code live_schema.audit_logs} 로 <b>도메인 공용</b>이고
     * (정본 DDL 이 "공통 (도메인 전용 outbox / audit)" 아래 두었다) 지금 그 테이블에 쓰는 코드는
     * chat 뿐이다 — 그래서 {@code action} 값에 {@code CHAT_} 접두가 붙는다.
     */
    @Bean
    public ChatAuditLogRepository chatAuditLogRepository(ChatAuditLogJpaRepository jpaRepository) {
        return new ChatAuditLogRepositoryImpl(jpaRepository);
    }

    /**
     * 감사 한 줄에 트랜잭션 경계만 주는 빈 — <b>DB 트랜잭션이 없는 조치 둘</b>(강퇴 명단 재적재·
     * 종료 마커 해제)이 쓴다. 나머지 셋은 자기 recorder 의 트랜잭션 안에서 저장소를 직접 부른다.
     */
    @Bean
    public ChatAuditRecorder chatAuditRecorder(ChatAuditLogRepository auditLogRepository) {
        return new ChatAuditRecorder(auditLogRepository);
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

    /**
     * 사전 정본 접근. 이 앱만 가진다 — streaming-app 에는 DataSource 가 없다.
     */
    @Bean
    public ChatFilterWordRepository chatFilterWordRepository(ChatFilterWordJpaRepository jpaRepository) {
        return new ChatFilterWordRepositoryImpl(jpaRepository);
    }

    /**
     * 사전 스냅샷 발행(블로킹). 받는 쪽은 streaming-app 이고, 두 앱이 같은 Redis 를 봐야 전파가
     * 성립한다 — 이 앱의 {@code spring.data.redis} 설정이 streaming-app 과 같은 인스턴스를
     * 가리켜야 한다(강퇴 이벤트 발행과 같은 전제다).
     */
    @Bean
    public ProfanityDictionarySnapshotPublisher profanityDictionarySnapshotPublisher(
            StringRedisTemplate redisTemplate) {
        return new ProfanityDictionarySnapshotRedisPublisher(redisTemplate);
    }

    /**
     * 번들 시드의 DB 쓰기를 감싸는 트랜잭션 빈 — 위 {@code chatKickRecorder} 와 같은 이유로
     * 별도 빈이다. 한 트랜잭션이어야 하는 이유는 따로 있다: 나누면 Pod 이 중간에 죽을 때
     * "비지 않았지만 절반인" 테이블이 남고, 그 뒤로는 비어 있지 않으니 다시 심지 않는다.
     */
    @Bean
    public ChatFilterWordSeedRecorder chatFilterWordSeedRecorder(ChatFilterWordRepository repository) {
        return new ChatFilterWordSeedRecorder(repository);
    }

    /**
     * 정본 → 스냅샷. 시드 트랜잭션과 <b>다른 빈</b>이고 호출 순서가 계약이다 — 트랜잭션 안에서
     * 발행하면 롤백된 단어가 함대에 깔린다.
     */
    @Bean
    public ProfanityDictionaryPublishService profanityDictionaryPublishService(
            ChatFilterWordRepository repository,
            ProfanityDictionarySnapshotPublisher snapshotPublisher,
            ProfanityFilterFactory filterFactory) {
        return new ProfanityDictionaryPublishService(repository, snapshotPublisher, filterFactory);
    }

    /**
     * 사전의 <b>문</b>. 쓰기 경로의 선검사가 이것을 쓴다 — 번들·설정·전파가 지나는 문과 같아야
     * 하므로 chat-core 의 구현을 그대로 세운다. 스테레오타입이 붙어 있지만 이 앱은
     * {@code com.sapari.chat} 을 스캔하지 않으므로 직접 등록해야 한다.
     */
    @Bean
    public ProfanityFilterFactory profanityFilterFactory() {
        return new DictionaryProfanityFilterFactory();
    }

    /**
     * 사전 한 줄 쓰기의 트랜잭션 빈 — 위 {@code chatKickRecorder} 와 같은 이유로 별도 빈이다
     * ({@code @Modifying} 쿼리가 트랜잭션을 요구하고, 자기 호출은 프록시를 타지 않는다).
     */
    @Bean
    public ChatFilterWordWriteRecorder chatFilterWordWriteRecorder(
            ChatFilterWordRepository repository, ChatAuditLogRepository auditLogRepository) {
        return new ChatFilterWordWriteRecorder(repository, auditLogRepository);
    }

    /**
     * 사전 관리(ADMIN). 쓰기가 발행으로 끝나므로 발행 서비스를 받는다 — 정본만 고치고 발행하지
     * 않으면 함대가 그 변경을 영원히 모른다(받는 쪽이 버전을 본다).
     */
    @Bean
    public ManageFilterWordsUseCase manageFilterWordsUseCase(
            ChatPermissionPolicy permissionPolicy,
            ChatFilterWordRepository repository,
            ChatFilterWordWriteRecorder writeRecorder,
            ProfanityDictionaryPublishService publishService,
            ProfanityFilterFactory filterFactory,
            TimeProvider timeProvider) {
        return new ManageFilterWordsService(permissionPolicy, repository, writeRecorder,
                publishService, filterFactory, timeProvider);
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
                                             ChatBanStateRepository banStateRepository,
                                             ChatAuditLogRepository auditLogRepository) {
        return new ChatKickRecorder(kickLogRepository, banStateRepository, auditLogRepository);
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
    public ChatBanReleaseRecorder chatBanReleaseRecorder(ChatBanStateRepository banStateRepository,
                                                         ChatAuditLogRepository auditLogRepository) {
        return new ChatBanReleaseRecorder(banStateRepository, auditLogRepository);
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
     *
     * <p>{@code GetLiveRoomUseCase} 를 받는 것은 <b>권한 때문이 아니다</b> — 재적재는 방 소유와
     * 무관한 ADMIN 전용이다. 끝난 방에 되살리면 회수 만료가 걷히고 다시 붙여 줄 경로가 없어서,
     * "되살릴 의미가 있는 방인가" 를 정본에 묻는다({@code ReconcileKickListService}).
     */
    @Bean
    public ReconcileKickListUseCase reconcileKickListUseCase(ChatPermissionPolicy permissionPolicy,
                                                             GetLiveRoomUseCase liveRoomReader,
                                                             ChatKickLogRepository kickLogRepository,
                                                             ChatKickWriteRepository kickWriteRepository,
                                                             ChatAuditRecorder auditRecorder,
                                                             TimeProvider timeProvider) {
        return new ReconcileKickListService(permissionPolicy, liveRoomReader, kickLogRepository,
                kickWriteRepository, auditRecorder, timeProvider);
    }

    /**
     * 종료 마커 해제 어댑터(블로킹). 기록·조회는 리액티브 쪽이 하고 streaming-app 이 쓴다 —
     * 해제만 이 앱에 있는 이유는 진위 검증(그 방이 실제로 진행 중인가)이 live 정본을 읽어야 하고,
     * 그걸 읽을 수 있는 스택이 여기뿐이기 때문이다.
     */
    @Bean
    public ChatRoomEndedWriteRepository chatRoomEndedWriteRepository(StringRedisTemplate redisTemplate) {
        return new ChatRoomEndedWriteRedisRepository(redisTemplate);
    }

    @Bean
    public ClearRoomEndedMarkerUseCase clearRoomEndedMarkerUseCase(
            GetLiveRoomUseCase liveRoomReader,
            ChatRoomEndedWriteRepository roomEndedWriteRepository,
            ChatAuditRecorder auditRecorder,
            ChatPermissionPolicy permissionPolicy,
            TimeProvider timeProvider) {
        return new ClearRoomEndedMarkerService(liveRoomReader, roomEndedWriteRepository,
                auditRecorder, permissionPolicy, timeProvider);
    }
}
