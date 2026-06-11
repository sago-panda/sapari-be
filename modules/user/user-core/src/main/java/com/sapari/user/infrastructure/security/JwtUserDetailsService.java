package com.sapari.user.infrastructure.security;

import lombok.RequiredArgsConstructor;

import java.util.UUID;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.sapari.user.port.UserAccountUseCase;
import com.sapari.user.view.UserView;

@Service
@RequiredArgsConstructor
public class JwtUserDetailsService implements UserDetailsService {

    private final UserAccountUseCase userAccountUseCase;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        UUID userId = parseUserId(username);

        UserView user = userAccountUseCase.findById(userId)
                .orElseThrow(() -> new UsernameNotFoundException("해당하는 유저가 없습니다."));

        return new JwtUserDetails(toUserDetailsInfo(user));
    }

    private JwtUserDetailsInfo toUserDetailsInfo(UserView user) {
        return new JwtUserDetailsInfo(
                user.userId(),
                user.role().name(),
                user.status().name()
        );
    }

    private UUID parseUserId(String username) {
        if (username == null || username.isBlank()) {
            throw new UsernameNotFoundException("유효하지 않은 사용자 ID입니다.");
        }

        try {
            return UUID.fromString(username);
        } catch (IllegalArgumentException e) {
            throw new UsernameNotFoundException("유효하지 않은 사용자 ID입니다.", e);
        }
    }
}
