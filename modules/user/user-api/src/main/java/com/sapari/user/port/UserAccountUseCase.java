package com.sapari.user.port;

import java.util.Optional;
import java.util.UUID;

import com.sapari.user.command.RegisterSellerCommand;
import com.sapari.user.command.RegisterSocialMemberCommand;
import com.sapari.user.model.ProviderType;
import com.sapari.user.model.UserRole;
import com.sapari.user.view.UserView;

/**
 * 공유 식별자(User) 접근 포트. user-core가 구현하고 member/seller는 이 계약에만 의존한다.
 * 역할별 도메인 규칙(닉네임 변경 제한·역할 검증 등)은 호출자(member/seller)가 보유하고,
 * 이 포트는 영속성·조회·생성·매핑만 책임진다.
 */
public interface UserAccountUseCase {

    UserView registerSocialMember(RegisterSocialMemberCommand command);

    UserView registerSeller(RegisterSellerCommand command);

    Optional<UserView> findById(UUID userId);

    Optional<UserView> findBySocialAccount(ProviderType provider, String providerId);

    Optional<UserView> findByEmailAndRole(String email, UserRole role);

    boolean existsByEmail(String email);

    boolean existsByPhoneNumber(String phoneNumber);

    boolean existsByNickname(String nickname);

    UserView changeNickname(UUID userId, String nickname);
}
