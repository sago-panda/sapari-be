package com.sapari.seller.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.sapari.global.time.TimeProvider;
import com.sapari.seller.command.SellerSignupCommand;
import com.sapari.seller.domain.model.LocalCredential;
import com.sapari.seller.model.SellerApprovalStatus;
import com.sapari.seller.model.SellerBusinessType;
import com.sapari.seller.domain.model.SellerProfile;
import com.sapari.seller.domain.repository.LocalCredentialRepository;
import com.sapari.seller.domain.repository.SellerProfileRepository;
import com.sapari.seller.view.SellerSignupResult;
import com.sapari.user.command.RegisterSellerCommand;
import com.sapari.user.model.UserGrade;
import com.sapari.user.model.UserRole;
import com.sapari.user.model.UserStatus;
import com.sapari.user.port.UserAccountUseCase;
import com.sapari.user.view.UserView;

@DisplayName("판매자 회원가입 저장 프로세서 테스트")
class SellerSignupProcessorTest {

    private static final String EMAIL = "seller@example.com";
    private static final String PASSWORD = "Password1!";
    private static final String PASSWORD_HASH = "hashed-password";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final UserAccountUseCase userAccountUseCase = mock(UserAccountUseCase.class);
    private final LocalCredentialRepository localCredentialRepository = mock(LocalCredentialRepository.class);
    private final SellerProfileRepository sellerProfileRepository = mock(SellerProfileRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final SellerSignupProcessor sellerSignupProcessor = new SellerSignupProcessor(
            userAccountUseCase,
            localCredentialRepository,
            sellerProfileRepository,
            passwordEncoder,
            new TimeProvider(Clock.fixed(NOW, ZoneOffset.UTC))
    );

    @Test
    @DisplayName("판매자 가입 저장을 하나의 프로세스로 처리한다")
    void signupSavesSellerUserLocalCredentialAndSellerProfile() {
        // given
        UUID userId = UUID.randomUUID();
        SellerSignupCommand command = signupCommand();
        when(userAccountUseCase.registerSeller(any(RegisterSellerCommand.class)))
                .thenReturn(sellerView(userId));
        when(passwordEncoder.encode(PASSWORD)).thenReturn(PASSWORD_HASH);
        when(localCredentialRepository.save(any(LocalCredential.class))).thenAnswer(invocation ->
                invocation.getArgument(0)
        );
        when(sellerProfileRepository.save(any(SellerProfile.class))).thenAnswer(invocation ->
                invocation.getArgument(0)
        );

        // when
        SellerSignupResult result = sellerSignupProcessor.signup(
                command,
                "사파리 상점",
                SellerBusinessType.INDIVIDUAL
        );

        // then
        assertThat(result.userId()).isEqualTo(userId);

        ArgumentCaptor<RegisterSellerCommand> commandCaptor = ArgumentCaptor.forClass(RegisterSellerCommand.class);
        verify(userAccountUseCase).registerSeller(commandCaptor.capture());
        assertThat(commandCaptor.getValue().email()).isEqualTo(EMAIL);
        assertThat(commandCaptor.getValue().nickname()).isEqualTo("seller");

        ArgumentCaptor<LocalCredential> credentialCaptor = ArgumentCaptor.forClass(LocalCredential.class);
        verify(localCredentialRepository).save(credentialCaptor.capture());
        assertThat(credentialCaptor.getValue().userId()).isEqualTo(userId);
        assertThat(credentialCaptor.getValue().passwordHash()).isEqualTo(PASSWORD_HASH);

        ArgumentCaptor<SellerProfile> profileCaptor = ArgumentCaptor.forClass(SellerProfile.class);
        verify(sellerProfileRepository).save(profileCaptor.capture());
        assertThat(profileCaptor.getValue().userId()).isEqualTo(userId);
        assertThat(profileCaptor.getValue().status()).isEqualTo(SellerApprovalStatus.PENDING);
        assertThat(profileCaptor.getValue().storeName()).isEqualTo("사파리 상점");
        assertThat(profileCaptor.getValue().businessNumber()).isEqualTo("1234567890");
        assertThat(profileCaptor.getValue().businessType()).isEqualTo(SellerBusinessType.INDIVIDUAL);
    }

    @Test
    @DisplayName("가입 정보 unique 충돌은 트랜잭션 호출부에서 변환할 수 있도록 그대로 전파한다")
    void signupThrowsDataIntegrityViolationExceptionWhenSignupInfoIsDuplicated() {
        // given
        when(userAccountUseCase.registerSeller(any(RegisterSellerCommand.class)))
                .thenThrow(new DataIntegrityViolationException("duplicated"));

        // when, then
        assertThatThrownBy(() -> sellerSignupProcessor.signup(
                signupCommand(),
                "사파리 상점",
                SellerBusinessType.INDIVIDUAL
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    private SellerSignupCommand signupCommand() {
        return new SellerSignupCommand(
                EMAIL,
                PASSWORD,
                "seller",
                "판매자",
                "01012345678",
                LocalDate.of(1990, 1, 1),
                true,
                "사파리 상점",
                "1234567890",
                LocalDate.of(2020, 1, 1),
                SellerBusinessType.INDIVIDUAL
        );
    }

    private UserView sellerView(UUID userId) {
        return new UserView(
                userId,
                UserRole.SELLER,
                UserStatus.ACTIVE,
                "seller",
                NOW,
                "판매자",
                LocalDate.of(1990, 1, 1),
                null,
                "01012345678",
                null,
                EMAIL,
                UserGrade.BRONZE,
                0,
                true,
                null,
                null,
                null
        );
    }
}
