package com.sapari.storage.object.s3;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import com.sapari.storage.object.client.ObjectStorageClient;
import com.sapari.storage.object.command.ObjectPutCommand;
import com.sapari.storage.object.config.S3ObjectStorageProperties;
import com.sapari.storage.object.exception.ObjectStorageException;
import com.sapari.storage.object.exception.ObjectStorageOperation;
import com.sapari.storage.object.result.StoredObject;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * S3-compatible object storage에 객체를 저장/삭제하는 공통 adapter다.
 * bucket은 공통 storage 설정에서 읽고, 도메인 모듈은 object key와 content metadata만 전달한다.
 */
@Component
@RequiredArgsConstructor
public class S3ObjectStorageClient implements ObjectStorageClient {

    private final S3Client objectStorageS3Client;
    private final S3ObjectStorageProperties properties;

    @Override
    public StoredObject put(ObjectPutCommand command) {
        try {
            objectStorageS3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(properties.bucket())
                            .key(command.key())
                            .contentType(command.contentType())
                            .contentLength((long) command.content().length)
                            .build(),
                    RequestBody.fromBytes(command.content())
            );
        } catch (SdkException e) {
            // AWS SDK 계열 실패만 저장소 장애로 분류하고 일반 RuntimeException은 숨기지 않는다.
            throw new ObjectStorageException(ObjectStorageOperation.PUT, command.key(), e);
        }
        return new StoredObject(
                command.key(),
                command.contentType(),
                command.content().length
        );
    }

    @Override
    public void delete(String key) {
        try {
            objectStorageS3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(key)
                    .build());
        } catch (SdkException e) {
            // 호출 도메인이 삭제 실패 정책을 선택할 수 있도록 SDK 세부 예외를 공통 계약으로 변환한다.
            throw new ObjectStorageException(ObjectStorageOperation.DELETE, key, e);
        }
    }
}
