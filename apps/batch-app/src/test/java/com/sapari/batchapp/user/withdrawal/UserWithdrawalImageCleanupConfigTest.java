package com.sapari.batchapp.user.withdrawal;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import com.sapari.storage.object.client.ObjectStorageClient;
import com.sapari.user.application.port.ProfileImageStorage;
import com.sapari.user.port.UserProfileImageCleanupUseCase;
import static org.assertj.core.api.Assertions.assertThat;

class UserWithdrawalImageCleanupConfigTest {
    /** 실제 배치 설정의 import로 정리 포트와 S3 어댑터가 등록되는지 확인하며 네트워크 호출은 하지 않는다. */
    @Test
    void wiresCleanupWithObjectStorage() {
        new ApplicationContextRunner()
                .withUserConfiguration(UserWithdrawalImageCleanupConfig.class)
                .withPropertyValues(
                        "sapari.storage.object.s3.endpoint=http://localhost:9090",
                        "sapari.storage.object.s3.region=ap-northeast-2",
                        "sapari.storage.object.s3.access-key=test",
                        "sapari.storage.object.s3.secret-key=test",
                        "sapari.storage.object.s3.path-style-access-enabled=true",
                        "sapari.storage.object.s3.bucket=profile-test",
                        "sapari.storage.object.s3.public-base-url=http://localhost:9090/profile-test")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(UserProfileImageCleanupUseCase.class);
                    assertThat(context).hasSingleBean(ProfileImageStorage.class);
                    assertThat(context).hasSingleBean(ObjectStorageClient.class);
                });
    }
}
