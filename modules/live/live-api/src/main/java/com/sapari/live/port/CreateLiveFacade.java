package com.sapari.live.port;

import org.springframework.stereotype.Component;

import com.sapari.live.command.CreateLiveCommand;
import com.sapari.live.view.CreateLiveView;

@Component
public interface CreateLiveFacade {
    CreateLiveView execute(CreateLiveCommand command);
}

