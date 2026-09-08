package com.sapari.batchapp.user.withdrawal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.sapari.seller.domain.repository.LocalCredentialRepository;
import com.sapari.seller.domain.repository.SellerProfileRepository;
import com.sapari.user.application.port.ProfileImageStorage;
import com.sapari.user.application.service.UserProfileImageCleanupService;
import com.sapari.user.domain.model.User;
import com.sapari.user.domain.repository.UserRepository;
import com.sapari.user.model.UserStatus;

class UserWithdrawalImageCleanupTransactionTest {
    private final UserRepository users = mock(UserRepository.class);
    private final ProfileImageStorage storage = mock(ProfileImageStorage.class);
    private final Connection connection = mock(Connection.class);
    private final UUID first = UUID.randomUUID();
    private final UUID second = UUID.randomUUID();
    private TransactionTemplate transaction;

    /** DB 접근만 대체하며 실제 트랜잭션 관리자와 정리 서비스를 조합한다. */
    @BeforeEach
    void setUp() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        when(users.findByIdForUpdate(first)).thenReturn(Optional.of(User.builder()
                .userId(first).status(UserStatus.WITHDRAWING).profileImageKey("first.jpg").build()));
        when(users.findByIdForUpdate(second)).thenReturn(Optional.of(User.builder()
                .userId(second).status(UserStatus.WITHDRAWING).profileImageKey("second.jpg").build()));
    }

    /** Spring이 새 포트를 주입한 Writer가 같은 청크의 사진을 커밋 이후에 정리한다. */
    @Test
    void wiredWriterDeletesBothImagesAfterCommit() throws Exception {
        try (AnnotationConfigApplicationContext context = context()) {
            UserWithdrawalHardDeleteWriter writer = context.getBean(UserWithdrawalHardDeleteWriter.class);
            transaction.executeWithoutResult(status -> {
                writer.write(new Chunk<>(List.of(first, second)));
                verifyNoInteractions(storage);
            });
            InOrder order = inOrder(connection, storage);
            order.verify(connection).commit();
            order.verify(storage).deleteQuietly("first.jpg", "USER_HARD_DELETE");
            order.verify(storage).deleteQuietly("second.jpg", "USER_HARD_DELETE");
        }
    }

    /** 뒤의 회원 삭제 실패로 청크가 롤백되면 앞 회원의 사진 예약도 취소된다. */
    @Test
    void laterDatabaseFailurePreservesAllImages() throws Exception {
        doThrow(new IllegalStateException("database failure")).when(users).deleteById(second);
        try (AnnotationConfigApplicationContext context = context()) {
            UserWithdrawalHardDeleteWriter writer = context.getBean(UserWithdrawalHardDeleteWriter.class);
            assertThatThrownBy(() -> transaction.executeWithoutResult(status ->
                    writer.write(new Chunk<>(List.of(first, second)))))
                    .isInstanceOf(IllegalStateException.class);
            verify(connection).rollback();
            verifyNoInteractions(storage);
        }
    }

    /** 외부 의존 포트만 대체한 Spring 컨텍스트로 실제 서비스·Writer 주입을 확인한다. */
    private AnnotationConfigApplicationContext context() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(UserRepository.class, () -> users);
        context.registerBean(ProfileImageStorage.class, () -> storage);
        context.registerBean(LocalCredentialRepository.class, () -> mock(LocalCredentialRepository.class));
        context.registerBean(SellerProfileRepository.class, () -> mock(SellerProfileRepository.class));
        context.register(UserProfileImageCleanupService.class, UserWithdrawalHardDeleteWriter.class);
        context.refresh();
        return context;
    }
}
