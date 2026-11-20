package com.sapari.live.port;

import com.sapari.live.command.IssueThumbnailUploadCommand;
import com.sapari.live.view.ThumbnailUploadView;

public interface IssueThumbnailUploadUseCase {

    ThumbnailUploadView issue(IssueThumbnailUploadCommand command);
}
