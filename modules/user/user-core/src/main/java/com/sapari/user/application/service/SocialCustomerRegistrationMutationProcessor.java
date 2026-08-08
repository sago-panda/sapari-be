package com.sapari.user.application.service;

import lombok.RequiredArgsConstructor;

import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sapari.user.command.SocialCustomerRegistrationRollbackCommand;
import com.sapari.user.domain.model.User;
import com.sapari.user.domain.repository.UserRepository;
import com.sapari.user.domain.repository.UserTermsAgreementRepository;
import com.sapari.user.model.UserRole;

/**
 * 소셜 가입 후 후속 필수 처리 실패 시 이번 요청이 만든 user와 약관 증적을 보상 삭제하고 이미지 key를 돌려준다.
 */
@Service
@RequiredArgsConstructor
public class SocialCustomerRegistrationMutationProcessor {

    private final UserRepository userRepository;
    private final UserTermsAgreementRepository userTermsAgreementRepository;

    /** 저장된 가입 식별자가 모두 일치할 때만 약관 증적과 사용자를 삭제하고 정리할 이미지 key를 반환한다. */
    @Transactional
    public String rollback(SocialCustomerRegistrationRollbackCommand command) {
        User user = userRepository.findById(command.userId())
                .orElseThrow(() -> new IllegalStateException("registration user not found"));
        // 다른 사용자 삭제를 막는 마지막 방어선으로 이번 가입의 role/provider/providerId/email을 모두 대조한다.
        if (user.role() != UserRole.USER
                || user.provider() != command.provider()
                || !Objects.equals(user.providerId(), command.providerId())
                || !Objects.equals(user.email(), command.email())) {
            throw new IllegalStateException("registration identity mismatch");
        }

        String profileImageKey = user.profileImageKey();
        userTermsAgreementRepository.deleteByUserId(command.userId());
        userRepository.deleteById(command.userId());
        return profileImageKey;
    }
}
