package com.sapari.streamingapp.websocket;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import io.netty.channel.ChannelId;
import lombok.extern.slf4j.Slf4j;
import reactor.netty.Connection;

/**
 * 살아있는 TCP 커넥션을 채널 id로 찾을 수 있게 들고 있는다 — <b>close가 끝내 나가지 못한 연결을 회수</b>하기 위해서다.
 *
 * <p>WebSocketSession으로는 채널에 닿을 수 없다(채널 id만 공개돼 있다). 그래서 서버가 커넥션을 만들 때
 * 여기에 등록해 두고, 나중에 세션이 들고 있는 채널 id로 되찾는다.
 *
 * <p>서버의 idle timeout 설정은 이 자리를 대신하지 못한다 — 실측해보면 WebSocket 업그레이드 이후의
 * 연결에는 걸리지 않는다. 게다가 단순 idle 판정은 <b>말없이 보기만 하는 정상 시청자</b>를 함께 끊는다.
 * 여기서는 "서버가 이미 끊기로 결정했는데 못 끊은 연결"만 다루므로 그 오탐이 없다.
 */
@Slf4j
@Component
public class NettyConnectionRegistry {

    private final Map<ChannelId, Connection> connections = new ConcurrentHashMap<>();

    /** 서버가 커넥션을 수립할 때 호출된다(WS 업그레이드 전 단계라 모든 연결이 여기를 지난다). */
    public void register(Connection connection) {
        ChannelId id = connection.channel().id();
        connections.put(id, connection);
        // 정상 종료 경로에서 스스로 빠진다 — 이게 없으면 이 맵이 새로운 누수원이 된다.
        connection.onDispose(() -> connections.remove(id));
    }

    /**
     * 해당 채널을 강제로 끊는다. 이미 사라졌으면 무동작.
     *
     * <p>close 프레임을 기다리지 않고 소켓을 내린다 — 여기까지 온 건 그 프레임이 나갈 수 없다는 뜻이라
     * 더 기다려도 달라지지 않는다.
     */
    public void dispose(ChannelId channelId) {
        Connection connection = connections.remove(channelId);
        if (connection == null) {
            return;
        }
        log.warn("close 프레임이 나가지 못한 연결 강제 회수 channelId={}", channelId.asShortText());
        connection.dispose();
    }

    /** 추적 중인 커넥션 수. (테스트 진입점 — 맵이 스스로 비는지 확인용) */
    int trackedCount() {
        return connections.size();
    }
}
