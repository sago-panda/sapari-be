package com.sapari.seller.infrastructure.persistence.repository;

import static org.mockito.Mockito.verify;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sapari.seller.infrastructure.persistence.mapper.LocalCredentialMapper;
import com.sapari.seller.infrastructure.persistence.mapper.SellerProfileMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("탈퇴회원 판매자 데이터 삭제 Repository 테스트")
class SellerWithdrawalPurgeRepositoryTest {

    @Mock
    private LocalCredentialJpaRepository localCredentialJpaRepository;

    @Mock
    private LocalCredentialMapper localCredentialMapper;

    @Mock
    private SellerProfileJpaRepository sellerProfileJpaRepository;

    @Mock
    private SellerProfileMapper sellerProfileMapper;

    @Test
    @DisplayName("LocalCredentialRepository는 userId로 로컬 인증정보를 삭제한다")
    void localCredentialRepositoryDeletesByUserId() {
        // given
        UUID userId = UUID.randomUUID();
        LocalCredentialRepositoryImpl repository = new LocalCredentialRepositoryImpl(
                localCredentialJpaRepository,
                localCredentialMapper
        );

        // when
        repository.deleteByUserId(userId);

        // then
        verify(localCredentialJpaRepository).deleteById(userId);
    }

    @Test
    @DisplayName("SellerProfileRepository는 userId로 판매자 프로필을 삭제한다")
    void sellerProfileRepositoryDeletesByUserId() {
        // given
        UUID userId = UUID.randomUUID();
        SellerProfileRepositoryImpl repository = new SellerProfileRepositoryImpl(
                sellerProfileJpaRepository,
                sellerProfileMapper
        );

        // when
        repository.deleteByUserId(userId);

        // then
        verify(sellerProfileJpaRepository).deleteByUserId(userId);
    }
}
