package com.sapari.user.infrastructure.persistence.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.sapari.user.infrastructure.persistence.entity.UserTermsAgreementEntity;

@Repository
public interface UserTermsAgreementJpaRepository extends JpaRepository<UserTermsAgreementEntity, UUID> {
}
