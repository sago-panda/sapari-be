package com.sapari.chat.port;

import java.util.Optional;

import com.sapari.chat.command.GetChatNoticeCommand;
import com.sapari.chat.view.ChatMessageView;

/**
 * 방의 현재 공지 조회.
 *
 * <p>구현은 {@code GetChatHistoryService} 가 {@code GetChatHistoryUseCase} 와 함께 맡는다. 그래서 이 포트를
 * 주입받으려면 그 서비스를 등록하는 {@code @Bean} 의 선언 반환형이 구현 클래스여야 한다 — 한쪽 인터페이스로
 * 두면 소비자가 먼저 생성되는 순서에서 주입이 실패한다(실측: {@code AnnotationConfigApplicationContext},
 * 인터페이스 반환형은 생성 순서에 따라 성공·실패가 갈리고 구현 클래스 반환형은 두 순서 모두 성공).
 */
@BlockingChat
public interface GetChatNoticeUseCase {

    /** 방의 가장 최근 공지 한 건(강퇴자 제외 + 이메일 게이팅). 없으면 빈 값. blocking — live-app(MVC)에서 호출. */
    Optional<ChatMessageView> findLatestNotice(GetChatNoticeCommand command);
}
