package com.sapari.customer.application.service;

import lombok.RequiredArgsConstructor;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.sapari.common.securityjwt.jwt.JwtTokenLifecycle;
import com.sapari.common.securityjwt.jwt.JwtTokenLifecycleException;
import com.sapari.common.securityjwt.jwt.JwtTokenPrincipal;
import com.sapari.customer.domain.exception.CustomerErrorCode;
import com.sapari.customer.domain.exception.CustomerException;
import com.sapari.user.view.UserView;

/**
 * 구매자 인증 flow가 공통 JWT 토큰 생명주기를 사용할 수 있게 연결하는 도메인 래퍼.
 * common 모듈은 CustomerException/UserView를 모르므로 여기서 변환한다.
 */
@Component
@RequiredArgsConstructor
public class CustomerJwtTokenAdapter {

    private final JwtTokenLifecycle jwtTokenLifecycle;

    /**
     * 구매자 정보를 JWT 발급용 snapshot으로 변환해 새 토큰 쌍을 발급한다.
     */
    public JwtTokenLifecycle.IssuedTokenPair issueTokenPair(UserView customer) {
        return jwtTokenLifecycle.issueTokenPair(toJwtTokenPrincipal(customer));
    }

    /**
     * Refresh Token 검증 실패를 구매자 도메인 예외로 변환한다.
     */
    public JwtTokenLifecycle.RefreshSession requireRefreshToken(String refreshToken) {
        try {
            return jwtTokenLifecycle.requireRefreshToken(refreshToken);
        } catch (JwtTokenLifecycleException e) {
            throw invalidRefreshToken(e);
        }
    }

    /**
     * 공통 RTR 처리 결과를 그대로 사용하되 실패 응답 코드는 구매자 도메인이 결정한다.
     */
    public JwtTokenLifecycle.RotatedRefreshToken rotateRefreshToken(
            JwtTokenLifecycle.RefreshSession refreshSession,
            UserView customer
    ) {
        try {
            return jwtTokenLifecycle.rotateRefreshToken(refreshSession, toJwtTokenPrincipal(customer));
        } catch (JwtTokenLifecycleException e) {
            throw invalidRefreshToken(e);
        }
    }

    /**
     * Access Token 검증 실패를 구매자 도메인 예외로 변환한다.
     */
    public JwtTokenLifecycle.AccessSession requireAccessToken(String accessToken) {
        try {
            return jwtTokenLifecycle.requireAccessToken(accessToken);
        } catch (JwtTokenLifecycleException e) {
            throw invalidAccessToken(e);
        }
    }

    /**
     * 로그아웃 대상 Access Token을 검증하고 같은 sid 세션을 폐기한다.
     */
    public void revokeSession(String accessToken) {
        try {
            jwtTokenLifecycle.revokeSession(accessToken);
        } catch (JwtTokenLifecycleException e) {
            throw invalidAccessToken(e);
        }
    }

    /**
     * 회원 탈퇴 시 userId에 묶인 모든 기기의 Refresh Token과 Access Token 세션을 폐기한다.
     */
    public void revokeAllSessions(UUID userId) {
        jwtTokenLifecycle.revokeAllSessions(userId);
    }

    /**
     * 닉네임 snapshot 변경 후 기존 Access Token을 폐기하고 같은 sid로 새 Access Token을 발급한다.
     */
    public String replaceAccessTokenForNickname(
            JwtTokenLifecycle.AccessSession accessSession,
            UserView savedCustomer
    ) {
        try {
            return jwtTokenLifecycle.replaceAccessToken(accessSession, toJwtTokenPrincipal(savedCustomer));
        } catch (JwtTokenLifecycleException e) {
            throw invalidAccessToken(e);
        }
    }

    private JwtTokenPrincipal toJwtTokenPrincipal(UserView customer) {
        return new JwtTokenPrincipal(
                customer.userId(),
                customer.role().name(),
                customer.nickname(),
                customer.email()
        );
    }

    private CustomerException invalidRefreshToken(Throwable cause) {
        return new CustomerException(CustomerErrorCode.INVALID_REFRESH_TOKEN, cause);
    }

    private CustomerException invalidAccessToken(Throwable cause) {
        return new CustomerException(CustomerErrorCode.INVALID_ACCESS_TOKEN, cause);
    }
}
