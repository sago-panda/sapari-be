package com.sapari.user.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import com.sapari.user.model.ProviderType;
import com.sapari.user.model.UserGender;
import com.sapari.user.model.UserGrade;
import com.sapari.user.model.UserRole;
import com.sapari.user.model.UserStatus;
import com.sapari.user.port.UserAccountUseCase;
import com.sapari.user.view.UserView;

@DisplayName("JWT 사용자 조회 서비스 테스트")
class JwtUserDetailsServiceTest {

    private final UserAccountUseCase userAccountUseCase = org.mockito.Mockito.mock(UserAccountUseCase.class);
    private final JwtUserDetailsService jwtUserDetailsService = new JwtUserDetailsService(userAccountUseCase);

    @Test
    @DisplayName("유효한 UUID username이면 User를 조회해 JwtUserDetails를 반환한다")
    void loadUserByUsernameReturnsJwtUserDetailsWhenUserExists() {
        // given
        UUID userId = UUID.randomUUID();
        UserView user = createUserView(userId);
        when(userAccountUseCase.findById(userId)).thenReturn(Optional.of(user));

        // when
        JwtUserDetails userDetails = (JwtUserDetails) jwtUserDetailsService.loadUserByUsername(userId.toString());

        // then
        assertThat(userDetails.getUsername()).isEqualTo(userId.toString());
        assertThat(userDetails.getUser().role()).isEqualTo("USER");
        assertThat(userDetails.getUser().status()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("username이 비어 있으면 UsernameNotFoundException이 발생한다")
    void loadUserByUsernameThrowsExceptionWhenUsernameIsBlank() {
        // given
        String blankUsername = " ";

        // when, then
        assertThatThrownBy(() -> jwtUserDetailsService.loadUserByUsername(blankUsername))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    @DisplayName("username이 UUID 형식이 아니면 UsernameNotFoundException이 발생한다")
    void loadUserByUsernameThrowsExceptionWhenUsernameIsNotUuid() {
        // given
        String invalidUsername = "invalid-user-id";

        // when, then
        assertThatThrownBy(() -> jwtUserDetailsService.loadUserByUsername(invalidUsername))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    @DisplayName("조회된 User가 없으면 UsernameNotFoundException이 발생한다")
    void loadUserByUsernameThrowsExceptionWhenUserDoesNotExist() {
        // given
        UUID userId = UUID.randomUUID();
        when(userAccountUseCase.findById(userId)).thenReturn(Optional.empty());

        // when, then
        assertThatThrownBy(() -> jwtUserDetailsService.loadUserByUsername(userId.toString()))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    private UserView createUserView(UUID userId) {
        return new UserView(
                userId,
                UserRole.USER,
                UserStatus.ACTIVE,
                "tester",
                Instant.parse("2025-01-01T00:00:00Z"),
                "테스터",
                LocalDate.of(1995, 5, 15),
                UserGender.MALE,
                "01012345678",
                null,
                "tester@example.com",
                UserGrade.BRONZE,
                0,
                false,
                ProviderType.KAKAO,
                "provider-id",
                "provider@example.com"
        );
    }
}
