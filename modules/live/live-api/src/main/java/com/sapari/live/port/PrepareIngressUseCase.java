package com.sapari.live.port;

import com.sapari.live.command.PrepareIngressCommand;
import com.sapari.live.view.IngressCredentialView;

public interface PrepareIngressUseCase {
    IngressCredentialView prepare(PrepareIngressCommand command);
}
