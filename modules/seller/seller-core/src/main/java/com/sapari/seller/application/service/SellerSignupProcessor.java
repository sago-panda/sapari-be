package com.sapari.seller.application.service;

import lombok.RequiredArgsConstructor;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sapari.global.time.TimeProvider;
import com.sapari.seller.command.SellerSignupCommand;
import com.sapari.seller.domain.model.LocalCredential;
import com.sapari.seller.model.SellerBusinessType;
import com.sapari.seller.domain.model.SellerProfile;
import com.sapari.seller.domain.repository.LocalCredentialRepository;
import com.sapari.seller.domain.repository.SellerProfileRepository;
import com.sapari.seller.view.SellerSignupResult;
import com.sapari.user.command.RegisterSellerCommand;
import com.sapari.user.port.UserAccountUseCase;
import com.sapari.user.view.UserView;

@Service
@RequiredArgsConstructor
public class SellerSignupProcessor {

    private final UserAccountUseCase userAccountUseCase;
    private final LocalCredentialRepository localCredentialRepository;
    private final SellerProfileRepository sellerProfileRepository;
    private final PasswordEncoder passwordEncoder;
    private final TimeProvider timeProvider;

    /**
     * 판매자 가입에 필요한 User, LocalCredential, SellerProfile 저장을 하나의 트랜잭션으로 처리한다.
     */
    @Transactional
    public SellerSignupResult signup(
            SellerSignupCommand command,
            String normalizedStoreName,
            SellerBusinessType businessType
    ) {
        UserView savedUser = userAccountUseCase.registerSeller(toRegisterCommand(command));
        LocalCredential localCredential = LocalCredential.create(
                savedUser.userId(),
                passwordEncoder.encode(command.password()),
                timeProvider.now()
        );
        SellerProfile sellerProfile = SellerProfile.createPending(
                savedUser.userId(),
                normalizedStoreName,
                command.businessNumber(),
                businessType
        );

        localCredentialRepository.save(localCredential);
        sellerProfileRepository.save(sellerProfile);

        return new SellerSignupResult(savedUser.userId());
    }

    private RegisterSellerCommand toRegisterCommand(SellerSignupCommand command) {
        return new RegisterSellerCommand(
                command.nickname(),
                command.name(),
                command.phoneNumber(),
                command.email(),
                command.privacyAgreed(),
                command.marketingAgreed()
        );
    }
}
