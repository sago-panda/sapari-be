package com.sapari.user.application.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.sapari.user.application.port.ProfileImageStorage;
import com.sapari.user.application.support.ProfileImageUploadValidator;
import com.sapari.user.domain.exception.UserException;
import com.sapari.user.view.PreparedProfileImage;

class PreparedProfileImageBoundaryTest {

    /** 공개 record 생성자로 위조한 이미지가 저장소에 도달하지 않는지 검증한다. */
    @Test
    void rejectsForgedPreparedImageBeforeUpload() {
        ProfileImageStorage storage = mock(ProfileImageStorage.class);
        ProfileImageMutationProcessor mutation = mock(ProfileImageMutationProcessor.class);
        UserAccountService service = new UserAccountService(null, null, null, null, null,
                new ProfileImageUploadValidator(), storage, mutation, null, null, null);
        PreparedProfileImage forged = new PreparedProfileImage("png", "image/png", new byte[] {1, 2, 3});

        assertThatThrownBy(() -> service.changePreparedProfileImage(UUID.randomUUID(), forged))
                .isInstanceOf(UserException.class);
        verifyNoInteractions(storage, mutation);
    }
}
