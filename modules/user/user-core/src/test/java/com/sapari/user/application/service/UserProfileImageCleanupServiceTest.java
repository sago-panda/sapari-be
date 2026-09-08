package com.sapari.user.application.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.sql.Connection;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.sapari.user.application.port.ProfileImageStorage;
import com.sapari.user.infrastructure.storage.ObjectStorageProfileImageStorage;
import com.sapari.user.infrastructure.storage.ProfileImageObjectKeyGenerator;
import com.sapari.storage.object.client.ObjectStorageClient;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

class UserProfileImageCleanupServiceTest {
    private final ProfileImageStorage storage = mock(ProfileImageStorage.class);
    private final UserProfileImageCleanupService service = new UserProfileImageCleanupService(storage);
    private final Connection connection = mock(Connection.class);
    private TransactionTemplate transaction;

    /** JDBC 경계만 대체하고 Spring의 실제 커밋·롤백 콜백 처리를 사용한다. */
    @BeforeEach
    void setUp() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    /** 커밋 전에는 사진을 유지하고 실제 JDBC 커밋 이후 삭제한다. */
    @Test
    void deletesOnlyAfterCommit() throws Exception {
        transaction.executeWithoutResult(status -> {
            service.scheduleAfterCommit("users/one/profile/photo.jpg");
            verifyNoInteractions(storage);
        });
        InOrder order = inOrder(connection, storage);
        order.verify(connection).commit();
        order.verify(storage).deleteQuietly("users/one/profile/photo.jpg", "USER_HARD_DELETE");
    }

    /** 청크가 롤백되면 모든 사진 정리 예약을 버린다. */
    @Test
    void rollbackPreservesAllImages() {
        transaction.executeWithoutResult(status -> {
            service.scheduleAfterCommit("first.jpg");
            service.scheduleAfterCommit("second.jpg");
            status.setRollbackOnly();
        });
        verifyNoInteractions(storage);
    }

    /** 트랜잭션 없이 호출하면 즉시 삭제하는 대신 사용 오류를 알린다. */
    @Test
    void rejectsMissingTransaction() {
        assertThatThrownBy(() -> service.scheduleAfterCommit("photo.jpg"))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(storage);
    }

    /** 사진이 없는 회원은 커밋 후에도 스토리지 요청을 하지 않는다. */
    @Test
    void skipsAbsentImages() {
        transaction.executeWithoutResult(status -> {
            service.scheduleAfterCommit(null);
            service.scheduleAfterCommit(" ");
        });
        verifyNoInteractions(storage);
    }

    /** 실제 저장 어댑터가 한 삭제 실패를 안전하게 기록하고 다음 콜백을 계속 실행한다. */
    @Test
    void logsStorageFailureAndContinuesWithNextImage() {
        ObjectStorageClient client = mock(ObjectStorageClient.class);
        ObjectStorageProfileImageStorage realStorage = new ObjectStorageProfileImageStorage(client, new ProfileImageObjectKeyGenerator());
        UserProfileImageCleanupService cleanup = new UserProfileImageCleanupService(realStorage);
        doThrow(new IllegalStateException("secret-storage-message")).when(client).delete("first.jpg");
        Logger logger = (Logger) LoggerFactory.getLogger(ObjectStorageProfileImageStorage.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            transaction.executeWithoutResult(status -> {
                cleanup.scheduleAfterCommit("first.jpg");
                cleanup.scheduleAfterCommit("second.jpg");
                verifyNoInteractions(client);
            });
            verify(client).delete("first.jpg");
            verify(client).delete("second.jpg");
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                        .contains("USER_HARD_DELETE", "first.jpg", "IllegalStateException")
                        .doesNotContain("secret-storage-message");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
