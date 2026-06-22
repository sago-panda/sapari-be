package com.sapari.user.port;

import java.util.Optional;
import java.util.UUID;

import com.sapari.user.command.RegisterSellerCommand;
import com.sapari.user.command.RegisterSocialCustomerCommand;
import com.sapari.user.model.ProviderType;
import com.sapari.user.model.UserRole;
import com.sapari.user.view.UserView;

/**
 * 공유 식별자(User) 접근 포트. user-core가 구현하고 customer/seller는 이 계약에만 의존한다.
 * 역할별 도메인 규칙(닉네임 변경 제한·역할 검증 등)은 호출자(customer/seller)가 보유하고,
 * 이 포트는 영속성·조회·생성·매핑만 책임진다.
 */
public interface UserAccountUseCase {

    /**
     * 구매자 회원가입을 처리하고 가입 시점의 약관 증적까지 같은 성공 조건으로 저장한다.
     * 약관 동의는 회원가입의 필수 정책이므로 별도 우회 포트를 두지 않는다.
     */
    UserView registerSocialCustomer(RegisterSocialCustomerCommand command);

    /**
     * 판매자 회원가입을 처리하고 가입 시점의 약관 증적까지 같은 성공 조건으로 저장한다.
     * 약관 동의는 회원가입의 필수 정책이므로 별도 우회 포트를 두지 않는다.
     */
    UserView registerSeller(RegisterSellerCommand command);

    Optional<UserView> findById(UUID userId);

    Optional<UserView> findBySocialAccount(ProviderType provider, String providerId);

    Optional<UserView> findByEmailAndRole(String email, UserRole role);

    boolean existsByEmail(String email);

    boolean existsByPhoneNumber(String phoneNumber);

    boolean existsByNickname(String nickname);

    UserView changeNickname(UUID userId, String nickname);

    /**
     * 회원탈퇴를 신청해 계정을 탈퇴 유예 상태로 전환한다.
     * 구현체는 법정 보존용 마스킹 정보를 함께 남긴다.
     */
    UserView requestWithdrawal(UUID userId);
}
