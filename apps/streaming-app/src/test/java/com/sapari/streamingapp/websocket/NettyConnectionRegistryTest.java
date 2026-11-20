package com.sapari.streamingapp.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.netty.channel.Channel;
import io.netty.channel.ChannelId;
import io.netty.channel.DefaultChannelId;
import reactor.core.Disposable;
import reactor.netty.Connection;

class NettyConnectionRegistryTest {

    private final NettyConnectionRegistry registry = new NettyConnectionRegistry();

    /** 채널 id를 가진 커넥션 목. onDispose 콜백은 잡아뒀다가 테스트가 직접 발화한다. */
    private Connection connection(ChannelId id, Runnable[] onDisposeSlot) {
        Channel channel = mock(Channel.class);
        given(channel.id()).willReturn(id);
        Connection connection = mock(Connection.class);
        given(connection.channel()).willReturn(channel);
        given(connection.onDispose(any(Disposable.class))).willAnswer(inv -> {
            Disposable d = inv.getArgument(0);
            onDisposeSlot[0] = d::dispose;
            return connection;
        });
        return connection;
    }

    @Test
    @DisplayName("등록한 커넥션을 채널 id로 찾아 끊는다")
    void dispose_closes_registered_connection() {
        // given
        ChannelId id = DefaultChannelId.newInstance();
        Connection connection = connection(id, new Runnable[1]);
        registry.register(connection);

        // when
        registry.dispose(id);

        // then
        then(connection).should(times(1)).dispose();
        assertThat(registry.trackedCount()).isZero();   // 회수 후 추적도 끝난다
    }

    @Test
    @DisplayName("정상 종료된 커넥션은 스스로 빠진다 — 이 맵이 새 누수원이 되면 안 된다")
    void normal_close_removes_itself() {
        // given
        Runnable[] onDispose = new Runnable[1];
        registry.register(connection(DefaultChannelId.newInstance(), onDispose));
        assertThat(registry.trackedCount()).isOne();

        // when: 서버가 커넥션을 정상적으로 닫는다
        onDispose[0].run();

        // then
        assertThat(registry.trackedCount()).isZero();
    }

    @Test
    @DisplayName("모르는 채널 id는 무동작 — 이미 사라진 커넥션에 대해 터지지 않는다")
    void dispose_unknown_channel_is_noop() {
        // when & then
        registry.dispose(DefaultChannelId.newInstance());
        assertThat(registry.trackedCount()).isZero();
    }
}
