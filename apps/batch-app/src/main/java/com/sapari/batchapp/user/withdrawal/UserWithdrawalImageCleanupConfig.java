package com.sapari.batchapp.user.withdrawal;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import com.sapari.storage.object.config.S3ObjectStorageConfig;
import com.sapari.storage.object.s3.S3ObjectStorageClient;
import com.sapari.user.application.service.UserProfileImageCleanupService;
import com.sapari.user.infrastructure.storage.ObjectStorageProfileImageStorage;
import com.sapari.user.infrastructure.storage.ProfileImageObjectKeyGenerator;

/** 제한된 배치 컴포넌트 스캔에 영구 삭제 사진 정리와 필요한 저장소 빈만 연결한다. */
@Configuration(proxyBeanMethods = false)
@Import({UserProfileImageCleanupService.class, ObjectStorageProfileImageStorage.class,
        ProfileImageObjectKeyGenerator.class, S3ObjectStorageConfig.class, S3ObjectStorageClient.class})
public class UserWithdrawalImageCleanupConfig {
}
