package com.sapari.apiapp.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.sapari.common.securityjwt.jwt.JwtTokenLifecycle.AccessSession;
import com.sapari.customer.application.mapper.CustomerViewMapper;
import com.sapari.customer.application.service.CustomerAuthService;
import com.sapari.customer.application.service.CustomerJwtTokenAdapter;
import com.sapari.customer.command.CustomerNicknameUpdateCommand;
import com.sapari.global.time.TimeProvider;
import com.sapari.seller.application.mapper.SellerViewMapper;
import com.sapari.seller.application.service.SellerAuthService;
import com.sapari.seller.application.service.SellerJwtTokenAdapter;
import com.sapari.seller.command.SellerNicknameUpdateCommand;
import com.sapari.seller.domain.model.SellerProfile;
import com.sapari.seller.domain.repository.SellerProfileRepository;
import com.sapari.user.model.UserRole;
import com.sapari.user.port.UserAccountUseCase;
import com.sapari.user.view.UserView;

class UserMutationTransactionBoundaryTest {

    /** 실제 Spring 프록시에서 바깥 트랜잭션을 중단하고 DB 커밋 후 외부 작업을 실행한다. */
    @ParameterizedTest
    @CsvSource({"false,false,false", "false,true,false", "true,false,false", "true,true,false",
            "false,false,true", "false,true,true", "true,false,true", "true,true,true"})
    void commitsBeforeExternalWorkEvenWhenItFails(boolean seller, boolean withdrawal, boolean fail) throws Exception {
        // JDBC 연결만 대체하며 트랜잭션 시작·중단·재개·커밋은 실제 Spring 구현으로 검증한다.
        DataSource source = mock(DataSource.class);
        when(source.getConnection()).thenAnswer(call -> {
            Connection connection = mock(Connection.class);
            when(connection.getAutoCommit()).thenReturn(true);
            return connection;
        });
        var manager = new DataSourceTransactionManager(source);
        var transaction = new TransactionTemplate(manager);
        AtomicBoolean committed = new AtomicBoolean();
        AtomicBoolean externalCalled = new AtomicBoolean();
        IllegalStateException failure = new IllegalStateException("external unavailable");
        UserAccountUseCase users = mock(UserAccountUseCase.class);
        UserView user = mock(UserView.class);
        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-07T00:00:00Z");
        TimeProvider time = new TimeProvider(Clock.fixed(now, ZoneOffset.UTC));
        when(user.userId()).thenReturn(id);
        when(user.role()).thenReturn(seller ? UserRole.SELLER : UserRole.USER);
        when(user.nicknameChangedAt()).thenReturn(now.minus(Duration.ofDays(31)));
        when(users.findById(id)).thenReturn(Optional.of(user));
        org.mockito.stubbing.Answer<UserView> mutation = call -> transaction.execute(status -> {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                /** 실제 mutation 트랜잭션의 커밋 이후에만 완료 표시를 세운다. */
                @Override
                public void afterCommit() {
                    committed.set(true);
                }
            });
            return user;
        });
        when(users.changeNickname(id, "updated", Duration.ofDays(30))).thenAnswer(mutation);
        when(users.requestWithdrawal(id)).thenAnswer(mutation);
        org.mockito.stubbing.Answer<String> external = call -> {
            assertThat(committed.get()).isTrue();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            externalCalled.set(true);
            if (fail) {
                throw failure;
            }
            return "new-access";
        };
        AccessSession session = new AccessSession(id, UUID.randomUUID(), UUID.randomUUID(), now.plusSeconds(3600));
        Runnable operation;
        if (seller) {
            SellerJwtTokenAdapter jwt = mock(SellerJwtTokenAdapter.class);
            when(jwt.requireAccessToken("access")).thenReturn(session);
            when(jwt.replaceAccessTokenForNickname(any(), any())).thenAnswer(external);
            doAnswer(external).when(jwt).revokeAllSessions(id);
            SellerProfileRepository profiles = mock(SellerProfileRepository.class);
            when(profiles.findByUserId(id)).thenReturn(Optional.of(mock(SellerProfile.class)));
            var target = new SellerAuthService(users, null, profiles, null, null, null, null,
                    jwt, time, mock(SellerViewMapper.class));
            SellerAuthService proxy = proxy(target, manager);
            operation = withdrawal ? () -> proxy.requestWithdrawal("access")
                    : () -> proxy.updateNickname(new SellerNicknameUpdateCommand("updated", "access"));
        } else {
            CustomerJwtTokenAdapter jwt = mock(CustomerJwtTokenAdapter.class);
            when(jwt.requireAccessToken("access")).thenReturn(session);
            when(jwt.replaceAccessTokenForNickname(any(), any())).thenAnswer(external);
            doAnswer(external).when(jwt).revokeAllSessions(id);
            var target = new CustomerAuthService(null, null, users, jwt, time, null,
                    mock(CustomerViewMapper.class), null, null, null);
            CustomerAuthService proxy = proxy(target, manager);
            operation = withdrawal ? () -> proxy.requestWithdrawal("access")
                    : () -> proxy.updateNickname(new CustomerNicknameUpdateCommand("updated", "access"));
        }
        if (fail) {
            assertThatThrownBy(() -> transaction.executeWithoutResult(status -> operation.run())).isSameAs(failure);
        } else {
            transaction.executeWithoutResult(status -> operation.run());
        }
        assertThat(committed.get()).isTrue();
        assertThat(externalCalled.get()).isTrue();
    }

    /** 서비스의 실제 트랜잭션 annotation을 적용한 프록시를 구성한다. */
    @SuppressWarnings("unchecked")
    private <T> T proxy(T target, DataSourceTransactionManager manager) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(manager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        factory.addAdvice(interceptor);
        return (T) factory.getProxy();
    }
}
