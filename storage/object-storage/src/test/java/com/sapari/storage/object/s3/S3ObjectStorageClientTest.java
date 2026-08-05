package com.sapari.storage.object.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sapari.storage.object.command.ObjectPutCommand;
import com.sapari.storage.object.config.S3ObjectStorageProperties;
import com.sapari.storage.object.exception.ObjectStorageException;
import com.sapari.storage.object.exception.ObjectStorageOperation;
import com.sapari.storage.object.result.StoredObject;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.core.exception.SdkClientException;

@ExtendWith(MockitoExtension.class)
@DisplayName("S3 object storage client 테스트")
class S3ObjectStorageClientTest {

    @Mock
    private S3Client s3Client;

    @Test
    @DisplayName("객체를 저장하면 설정 bucket과 command key/contentType/contentLength로 S3 putObject를 호출한다")
    void putStoresObjectUsingConfiguredBucketAndCommandKey() {
        S3ObjectStorageClient client = new S3ObjectStorageClient(s3Client, properties("sapari-assets"));
        byte[] content = "image-bytes".getBytes();
        ObjectPutCommand command = new ObjectPutCommand("users/user-id/profile/image.jpg", "image/jpeg", content);
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        StoredObject storedObject = client.put(command);

        ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(requestCaptor.capture(), any(RequestBody.class));
        PutObjectRequest request = requestCaptor.getValue();
        assertThat(request.bucket()).isEqualTo("sapari-assets");
        assertThat(request.key()).isEqualTo("users/user-id/profile/image.jpg");
        assertThat(request.contentType()).isEqualTo("image/jpeg");
        assertThat(request.contentLength()).isEqualTo(content.length);
        assertThat(storedObject.key()).isEqualTo("users/user-id/profile/image.jpg");
        assertThat(storedObject.contentType()).isEqualTo("image/jpeg");
        assertThat(storedObject.contentLength()).isEqualTo(content.length);
    }

    @Test
    @DisplayName("객체를 삭제하면 설정 bucket과 key로 S3 deleteObject를 호출한다")
    void deleteDeletesObjectUsingConfiguredBucketAndKey() {
        S3ObjectStorageClient client = new S3ObjectStorageClient(s3Client, properties("sapari-assets"));

        client.delete("users/user-id/profile/image.jpg");

        ArgumentCaptor<DeleteObjectRequest> requestCaptor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(requestCaptor.capture());
        DeleteObjectRequest request = requestCaptor.getValue();
        assertThat(request.bucket()).isEqualTo("sapari-assets");
        assertThat(request.key()).isEqualTo("users/user-id/profile/image.jpg");
    }

    @Test
    @DisplayName("S3 객체 저장 실패를 공통 object storage 예외로 변환한다")
    void putMapsS3FailureToObjectStorageException() {
        S3ObjectStorageClient client = new S3ObjectStorageClient(s3Client, properties("sapari-assets"));
        ObjectPutCommand command = new ObjectPutCommand(
                "users/user-id/profile/image.jpg",
                "image/jpeg",
                "image-bytes".getBytes()
        );
        SdkClientException cause = SdkClientException.create("storage unavailable");
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenThrow(cause);

        assertThatThrownBy(() -> client.put(command))
                .isInstanceOfSatisfying(ObjectStorageException.class, exception -> {
                    assertThat(exception.operation()).isEqualTo(ObjectStorageOperation.PUT);
                    assertThat(exception.key()).isEqualTo(command.key());
                    assertThat(exception).hasCause(cause);
                });
    }

    @Test
    @DisplayName("S3 객체 삭제 실패를 공통 object storage 예외로 변환한다")
    void deleteMapsS3FailureToObjectStorageException() {
        S3ObjectStorageClient client = new S3ObjectStorageClient(s3Client, properties("sapari-assets"));
        String key = "users/user-id/profile/image.jpg";
        SdkClientException cause = SdkClientException.create("storage unavailable");
        when(s3Client.deleteObject(any(DeleteObjectRequest.class))).thenThrow(cause);

        assertThatThrownBy(() -> client.delete(key))
                .isInstanceOfSatisfying(ObjectStorageException.class, exception -> {
                    assertThat(exception.operation()).isEqualTo(ObjectStorageOperation.DELETE);
                    assertThat(exception.key()).isEqualTo(key);
                    assertThat(exception).hasCause(cause);
                });
    }

    @Test
    @DisplayName("객체 저장 명령은 빈 key, contentType과 null content를 거부한다")
    void objectPutCommandRejectsInvalidRequiredValues() {
        byte[] content = "image-bytes".getBytes();

        assertThatThrownBy(() -> new ObjectPutCommand(" ", "image/jpeg", content))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key");
        assertThatThrownBy(() -> new ObjectPutCommand("key", " ", content))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contentType");
        assertThatThrownBy(() -> new ObjectPutCommand("key", "image/jpeg", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("content");
    }

    private S3ObjectStorageProperties properties(String bucket) {
        return new S3ObjectStorageProperties(
                "http://localhost:9090",
                "ap-northeast-2",
                "local-access-key",
                "local-secret-key",
                true,
                bucket,
                "http://localhost:9090/sapari-local-assets"
        );
    }
}
