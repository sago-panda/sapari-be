package com.sapari.live.port;

import com.sapari.live.command.ReissueIngressCommand;
import com.sapari.live.view.IngressCredentialView;

/** 키를 받은 Scheduled RTMP 방의 송출 키를 새로 발급하고 이전 키를 무효로 만든다. */
public interface ReissueIngressUseCase {
    IngressCredentialView reissue(ReissueIngressCommand command);
}
