package com.sapari.live.port;

import com.sapari.live.command.LiveWebhookCommand;

/**
 * LiveKit webhook 수신 유스케이스. 컨트롤러가 raw 본문과 서명 헤더를 넘기면 검증 후 해당 이벤트를
 * 등록된 핸들러로 라우팅한다.
 */
public interface ProcessLiveWebhookUseCase {

    void process(LiveWebhookCommand command);
}
