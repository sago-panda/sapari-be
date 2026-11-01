package com.sapari.streamingapp.websocket;

import java.util.function.Function;

import com.sapari.chat.application.protocol.OutboundMessage;

/**
 * 한 팬아웃이 여러 세션에 싣는 <b>같은</b> 프레임 — 와이어 문자열을 한 번만 만들기 위한 그릇이다.
 *
 * <p><b>왜 필요한가.</b> 직렬화는 세션별 아웃바운드 파이프라인에서 일어난다
 * ({@code ChatWebSocketHandler} 가 {@code session.textMessage(serialize(..))} 로 감싼다). 그래서 방에
 * N 세션이 있으면 <b>같은 문자열을 N 번</b> 만든다. 반면 서로 다른 payload 는 몇 종뿐이다 —
 * {@code ChatBroadcastSubscriber.fanOutChat} 은 특권 뷰 × 발신자 축으로 <b>4종</b>을 미리 만들어
 * 세션마다 그중 하나를 고를 뿐이다. 객체 생성은 이미 O(종류)인데 직렬화만 O(세션)으로 남아 있었다.
 *
 * <p><b>실측</b>(NORMAL 뷰, 워밍업 후 20만 회):
 * <pre>
 * 직렬화 1회                0.77 us
 * 12,000 세션 × 1 메시지    9.23 ms   ← 세션마다 하던 비용
 * 4 종                      0.0031 ms
 * </pre>
 * 약 3,000 배다. 그리고 그 비용은 이벤트루프 경로에서 난다.
 *
 * <p><b>왜 큐에 문자열을 넣지 않는가.</b> 그러면 직렬화가 {@code emit} 시점으로 올라오는데
 * {@code emit} 도 세션마다 불린다 — 이득이 없다. 지연 계산을 <b>공유 객체에</b> 두는 쪽이라야
 * "먼저 소비한 세션이 만들고 나머지는 그걸 쓴다" 가 된다. 덤으로 아무도 소비하지 않은 프레임은
 * 직렬화되지 않는다(버퍼 초과로 끊긴 세션 등).
 *
 * <p><b>경합은 무해하다.</b> {@code wire} 는 {@code volatile} 이고 <b>같은 값으로 한 번만</b> 쓰인다 —
 * 두 세션이 동시에 들어오면 둘 다 만들 수 있지만 결과가 같다(같은 매퍼·같은 객체). 최악이 "N 번"
 * 대신 "몇 번" 이므로 잠금을 걸 이유가 없고, 잠금을 걸면 그게 이벤트루프에서 직렬화 지점이 된다.
 *
 * <p><b>남은 O(세션) 항 — 여기서 멈춘 이유를 숫자로 남긴다.</b> 직렬화를 걷어내면 다음 항은
 * {@code session.textMessage(String)} 의 <b>UTF-8 인코딩</b>이다(스프링 구현이 문자열을 인코딩해
 * 버퍼로 감싼다). 256바이트 payload 로 재면:
 * <pre>
 *                              지금(encode+wrap)   미리 인코딩+복사   미리 인코딩+공유
 * DefaultDataBufferFactory          129 ns              28.5 ns           7.6 ns
 * NettyDataBufferFactory(운영)      174 ns             151.8 ns          56.1 ns
 * </pre>
 * <b>운영 팩토리에서는 안전한 복사 변형이 13% 밖에 못 줄인다</b> — 복사가 인코딩보다 비싸다.
 * 3배 이득은 미리 인코딩한 배열을 세션 간 <b>공유</b>하는 쪽에만 있고, 그 안전성은 Netty 버퍼의
 * 수명·refcount 의미에 달려 있는데 이 저장소 테스트는 목 세션이라 그걸 재지 못한다. 그래서 하지
 * 않았다 — 검증할 수 없는 근거로 공유를 넣는 것이 이 자리에서 가장 비싼 선택이다.
 *
 * <p>⚠️ 테스트용 팩토리만 보면 {@code 129 → 28.5 ns}(4.5배)로 보인다. 그 수로 판단하면 운영에서
 * 거의 효과가 없는 변경을 넣는다. 다시 잴 때 <b>어느 팩토리로 재는지</b>를 먼저 정할 것.
 */
final class OutboundFrame {

    private final OutboundMessage message;

    /** 지연 계산된 와이어 문자열. 쓰기는 멱등이고 가시성만 필요해서 {@code volatile} 이다. */
    private volatile String wire;

    OutboundFrame(OutboundMessage message) {
        this.message = message;
    }

    /** 이 프레임이 나르는 메시지 — 기존 호출부·테스트가 보는 모양이다. */
    OutboundMessage message() {
        return message;
    }

    /** 와이어 문자열. 이 프레임을 공유하는 세션들 중 처음 부른 쪽이 만든다. */
    String wire(Function<OutboundMessage, String> serializer) {
        String cached = wire;
        if (cached == null) {
            cached = serializer.apply(message);
            wire = cached;
        }
        return cached;
    }
}
