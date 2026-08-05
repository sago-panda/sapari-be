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
 * 소셜 가입 후 후속 필수 처리 실패 시 이번 요청이 만든 user와 약관 증적만 보상 삭제한다.
 */
@Service
@RequiredArgsConstructor
public class SocialCustomerRegistrationMutationProcessor {

    private final UserRepository userRepository;
    private final UserTermsAgreementRepository userTermsAgreementRepository;

    /** 저장된 가입 식별자가 모두 일치할 때만 약관 증적과 사용자를 같은 트랜잭션에서 삭제한다. */
    @Transactional
    public void rollback(SocialCustomerRegistrationRollbackCommand command) {
        User user = userRepository.findById(command.userId())
                .orElseThrow(() -> new IllegalStateException("registration user not found"));
        // 다른 사용자 삭제를 막는 마지막 방어선으로 이번 가입의 role/provider/providerId/email을 모두 대조한다.
        if (user.role() != UserRole.USER
                || user.provider() != command.provider()
                || !Objects.equals(user.providerId(), command.providerId())
                || !Objects.equals(user.email(), command.email())) {
            throw new IllegalStateException("registration identity mismatch");
        }

        userTermsAgreementRepository.deleteByUserId(command.userId());
        userRepository.deleteById(command.userId());
    }
}
