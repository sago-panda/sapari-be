package com.sapari.chat;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

import com.sapari.chat.infrastructure.persistence.repository.ChatKickLogRepositoryImpl;

/**
 * chat-core 테스트 부트 설정. chat-core는 라이브러리 모듈이라 메인 애플리케이션이 없어 테스트 소스에만 둔다.
 *
 * <p>application.service·handler는 스캔에서 제외한다 — SendChatService(ChatBroadcaster 의존)·ChatBroadcastSubscriber
 * (ChatSessionManager 의존)는 소비 앱(streaming-app)이 제공하는 어댑터에 의존해 chat-core 단독 컨텍스트로는 와이어가
 * 불가능하다(헥사고날). 둘 다 Mockito 단위테스트로 검증하므로 통합 부트 컨텍스트(@SpringBootTest=Mongo 영속 테스트)에 넣을 필요가 없다.
 *
 * <p>{@code @SpringBootApplication}을 구성 애너테이션으로 분해한 이유: 별도 {@code @ComponentScan}을 덧붙이면
 * @SpringBootApplication 기본 스캔과 합쳐져 이중 스캔이 되어 제외 필터가 무력화된다. 단일 스캔만 두려 분해한다.
 * {@code @SpringBootConfiguration}은 유지해야 @SpringBootTest가 이 설정을 자동 탐지한다.
 *
 * <p>{@code TypeExcludeFilter}는 분해하면서 잃어버린 것을 되돌려 놓는 것이다 — {@code @SpringBootApplication}의
 * 기본 스캔에는 원래 붙어 있다. 이 필터가 {@code @DataJpaTest} 같은 슬라이스가 "나는 이 종류의 빈만 필요하다"를
 * 관철하는 통로라, 없으면 슬라이스를 걸어도 전체 스캔이 그대로 돌아 Mongo·Redis 어댑터까지 살아난다.
 *
 * <p>JPA 어댑터({@link ChatKickLogRepositoryImpl})도 같은 이유로 제외한다 — 이 컨텍스트에는 관계형 DataSource가
 * 없다. chat-core의 어댑터는 Mongo·Redis·Postgres 셋에 걸쳐 있어 하나의 컨텍스트로 전부 세우려면 매 테스트가
 * 컨테이너 셋을 요구하게 된다. JPA 어댑터는 자기 슬라이스에서 검증한다.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(excludeFilters = {
        @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = "com\\.sapari\\.chat\\.application\\.(service|handler)\\..*"),
        @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = ChatKickLogRepositoryImpl.class),
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class)})
public class ChatCoreTestApplication {
}
