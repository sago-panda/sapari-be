package com.sapari.user.infrastructure.persistence.repository;

import lombok.RequiredArgsConstructor;

import java.util.UUID;

import org.springframework.stereotype.Repository;

import com.sapari.user.domain.model.UserTermsAgreement;
import com.sapari.user.domain.repository.UserTermsAgreementRepository;
import com.sapari.user.infrastructure.persistence.entity.UserTermsAgreementEntity;
import com.sapari.user.infrastructure.persistence.mapper.UserTermsAgreementMapper;

@Repository
@RequiredArgsConstructor
public class UserTermsAgreementRepositoryImpl implements UserTermsAgreementRepository {

    private final UserTermsAgreementJpaRepository userTermsAgreementJpaRepository;
    private final UserTermsAgreementMapper userTermsAgreementMapper;

    @Override
    public UserTermsAgreement save(UserTermsAgreement agreement) {
        UserTermsAgreementEntity entity = userTermsAgreementMapper.toEntity(agreement);
        return userTermsAgreementMapper.toDomain(userTermsAgreementJpaRepository.save(entity));
    }

    /** 가입 보상 트랜잭션에서 해당 사용자의 약관 증적을 일괄 삭제한다. */
    @Override
    public void deleteByUserId(UUID userId) {
        userTermsAgreementJpaRepository.deleteByUserId(userId);
    }
}
