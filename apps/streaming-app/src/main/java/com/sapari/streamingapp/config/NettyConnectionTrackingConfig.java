package com.sapari.streamingapp.config;

import org.springframework.boot.reactor.netty.NettyReactiveWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.sapari.streamingapp.websocket.NettyConnectionRegistry;

/**
 * 수립되는 모든 서버 커넥션을 {@link NettyConnectionRegistry}에 등록한다.
 *
 * <p>WS 핸들러가 close를 보냈는데 flush되지 않아 소켓이 남는 경우, 그 연결을 되찾아 끊기 위한 배선이다.
 * 등록 자체는 부작용이 없고(맵에 넣고 종료 시 스스로 빠진다), 회수 여부는 핸들러가 판단한다.
 */
@Configuration
public class NettyConnectionTrackingConfig {

    @Bean
    public WebServerFactoryCustomizer<NettyReactiveWebServerFactory> trackConnections(
            NettyConnectionRegistry connections) {
        return factory -> factory.addServerCustomizers(
                server -> server.doOnConnection(connections::register));
    }
}
