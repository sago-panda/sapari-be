package com.sapari.chat;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

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
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(excludeFilters = @ComponentScan.Filter(
        type = FilterType.REGEX,
        pattern = "com\\.sapari\\.chat\\.application\\.(service|handler)\\..*"))
public class ChatCoreTestApplication {
}
