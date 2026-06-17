package com.sapari.chat;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * chat-core 슬라이스 테스트(@DataMongoTest 등)용 부트 설정.
 * chat-core는 라이브러리 모듈이라 메인 애플리케이션이 없어 테스트 소스에만 둔다.
 */
@SpringBootApplication
public class ChatCoreTestApplication {
}
