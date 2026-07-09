package com.sapari.user.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sapari.storage.object.command.ObjectPutCommand;
import com.sapari.storage.object.client.ObjectStorageClient;
import com.sapari.storage.object.result.StoredObject;
import com.sapari.user.application.dto.ProfileImageStoreCommand;
import com.sapari.user.application.dto.StoredProfileImage;

@ExtendWith(MockitoExtension.class)
@DisplayName("Object storage 기반 프로필 이미지 storage 테스트")
class ObjectStorageProfileImageStorageTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock
    private ObjectStorageClient objectStorageClient;

    @Test
    @DisplayName("프로필 이미지를 저장하면 회원별 key로 공통 object storage 저장을 호출하고 저장 결과를 반환한다")
    void storePutsProfileImageObjectAndReturnsStoredImage() {
        ObjectStorageProfileImageStorage storage = storage();
        byte[] content = "image-bytes".getBytes();
        ProfileImageStoreCommand command = new ProfileImageStoreCommand(USER_ID, "jpg", "image/jpeg", content);
        when(objectStorageClient.put(any(ObjectPutCommand.class)))
                .thenReturn(new StoredObject("users/user-id/profile/image.jpg", "image/jpeg", content.length));

        StoredProfileImage storedProfileImage = storage.store(command);

        ArgumentCaptor<ObjectPutCommand> commandCaptor = ArgumentCaptor.forClass(ObjectPutCommand.class);
        verify(objectStorageClient).put(commandCaptor.capture());
        ObjectPutCommand putCommand = commandCaptor.getValue();
        assertThat(putCommand.key())
                .matches("users/00000000-0000-0000-0000-000000000001/profile/[0-9a-f\\-]{36}\\.jpg");
        assertThat(putCommand.contentType()).isEqualTo("image/jpeg");
        assertThat(putCommand.content()).isEqualTo(content);
        assertThat(storedProfileImage.key()).isEqualTo(putCommand.key());
        assertThat(storedProfileImage.contentType()).isEqualTo("image/jpeg");
        assertThat(storedProfileImage.size()).isEqualTo(content.length);
    }

    @Test
    @DisplayName("프로필 이미지 key가 없으면 삭제 요청을 보내지 않는다")
    void deleteQuietlyDoesNothingWhenProfileImageKeyIsBlank() {
        ObjectStorageProfileImageStorage storage = storage();

        storage.deleteQuietly(null);
        storage.deleteQuietly(" ");

        verify(objectStorageClient, never()).delete(any());
    }

    @Test
    @DisplayName("기존 프로필 이미지를 삭제할 때 key로 공통 object storage delete를 호출한다")
    void deleteQuietlyDeletesProfileImageObject() {
        ObjectStorageProfileImageStorage storage = storage();

        storage.deleteQuietly("users/user-id/profile/old.jpg");

        verify(objectStorageClient).delete("users/user-id/profile/old.jpg");
    }

    @Test
    @DisplayName("삭제 실패는 기존 이미지 교체 흐름을 막지 않도록 삼킨다")
    void deleteQuietlySwallowsDeleteFailure() {
        ObjectStorageProfileImageStorage storage = storage();
        org.mockito.Mockito.doThrow(new IllegalStateException("delete failed"))
                .when(objectStorageClient).delete("users/user-id/profile/old.jpg");

        storage.deleteQuietly("users/user-id/profile/old.jpg");

        verify(objectStorageClient).delete("users/user-id/profile/old.jpg");
    }

    private ObjectStorageProfileImageStorage storage() {
        return new ObjectStorageProfileImageStorage(
                objectStorageClient,
                new ProfileImageObjectKeyGenerator()
        );
    }
}
