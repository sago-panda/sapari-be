package com.sapari.user.infrastructure.persistence.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.sapari.user.infrastructure.persistence.entity.UserTermsAgreementEntity;

@Repository
public interface UserTermsAgreementJpaRepository extends JpaRepository<UserTermsAgreementEntity, UUID> {

    /** 가입 보상 시 FK 순서를 지키기 위해 사용자 약관 증적을 먼저 삭제한다. */
    void deleteByUserId(UUID userId);
}
