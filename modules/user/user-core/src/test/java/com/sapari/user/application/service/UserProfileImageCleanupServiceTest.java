package com.sapari.user.application.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.sql.Connection;
import java.util.UUID;
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
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String KEY = "users/" + USER_ID + "/profile/11111111-1111-1111-1111-111111111111.jpg";
    private static final String SECOND_KEY = "users/" + USER_ID + "/profile/22222222-2222-2222-2222-222222222222.jpg";

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
        lenient().when(storage.isOwnedBy(eq(USER_ID), anyString())).thenReturn(true);
    }

    /** 커밋 전에는 사진을 유지하고 실제 JDBC 커밋 이후 삭제한다. */
    @Test
    void deletesOnlyAfterCommit() throws Exception {
        transaction.executeWithoutResult(status -> {
            service.scheduleAfterCommit(USER_ID, KEY);
            verify(storage, never()).deleteQuietly(anyString(), anyString());
        });
        InOrder order = inOrder(connection, storage);
        order.verify(connection).commit();
        order.verify(storage).deleteQuietly(KEY, "USER_HARD_DELETE");
    }

    /** 청크가 롤백되면 모든 사진 정리 예약을 버린다. */
    @Test
    void rollbackPreservesAllImages() {
        transaction.executeWithoutResult(status -> {
            service.scheduleAfterCommit(USER_ID, KEY);
            service.scheduleAfterCommit(USER_ID, SECOND_KEY);
            status.setRollbackOnly();
        });
        verify(storage, never()).deleteQuietly(anyString(), anyString());
    }

    /** 트랜잭션 없이 호출하면 즉시 삭제하는 대신 사용 오류를 알린다. */
    @Test
    void rejectsMissingTransaction() {
        assertThatThrownBy(() -> service.scheduleAfterCommit(USER_ID, KEY))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(storage);
    }

    /** 사진이 없는 회원은 커밋 후에도 스토리지 요청을 하지 않는다. */
    @Test
    void skipsAbsentImages() {
        transaction.executeWithoutResult(status -> {
            service.scheduleAfterCommit(USER_ID, null);
            service.scheduleAfterCommit(USER_ID, " ");
        });
        verifyNoInteractions(storage);
    }

    /** 다른 회원 경로의 key 는 커밋 뒤에도 지우지 않는다. */
    @Test
    void skipsKeyOwnedByAnotherUser() {
        UUID otherUserId = UUID.randomUUID();
        String foreignKey = "users/" + otherUserId + "/profile/11111111-1111-1111-1111-111111111111.jpg";
        ProfileImageObjectKeyGenerator keyGenerator = new ProfileImageObjectKeyGenerator();
        when(storage.isOwnedBy(USER_ID, foreignKey)).thenAnswer(inv -> keyGenerator.isOwnedBy(USER_ID, foreignKey));

        transaction.executeWithoutResult(status -> service.scheduleAfterCommit(USER_ID, foreignKey));

        verify(storage, never()).deleteQuietly(anyString(), anyString());
    }

    /** 실제 저장 어댑터가 한 삭제 실패를 안전하게 기록하고 다음 콜백을 계속 실행한다. */
    @Test
    void logsStorageFailureAndContinuesWithNextImage() {
        ObjectStorageClient client = mock(ObjectStorageClient.class);
        ObjectStorageProfileImageStorage realStorage = new ObjectStorageProfileImageStorage(client, new ProfileImageObjectKeyGenerator());
        UserProfileImageCleanupService cleanup = new UserProfileImageCleanupService(realStorage);
        doThrow(new IllegalStateException("secret-storage-message")).when(client).delete(KEY);
        Logger logger = (Logger) LoggerFactory.getLogger(ObjectStorageProfileImageStorage.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            transaction.executeWithoutResult(status -> {
                cleanup.scheduleAfterCommit(USER_ID, KEY);
                cleanup.scheduleAfterCommit(USER_ID, SECOND_KEY);
                verifyNoInteractions(client);
            });
            verify(client).delete(KEY);
            verify(client).delete(SECOND_KEY);
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                        .contains("USER_HARD_DELETE", KEY, "IllegalStateException")
                        .doesNotContain("secret-storage-message");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
