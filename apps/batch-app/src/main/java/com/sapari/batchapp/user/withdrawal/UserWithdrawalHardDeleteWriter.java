package com.sapari.batchapp.user.withdrawal;

import lombok.RequiredArgsConstructor;

import java.util.UUID;

import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.stereotype.Component;

import com.sapari.seller.domain.repository.LocalCredentialRepository;
import com.sapari.seller.domain.repository.SellerProfileRepository;
import com.sapari.user.domain.repository.UserRepository;

@Component
@RequiredArgsConstructor
public class UserWithdrawalHardDeleteWriter implements ItemWriter<UUID> {

    private final LocalCredentialRepository localCredentialRepository;
    private final SellerProfileRepository sellerProfileRepository;
    private final UserRepository userRepository;

    /**
     * 탈퇴 유예 기간이 끝난 사용자와 직접 연결된 인증·판매자 프로필 데이터를 삭제한 뒤 사용자 row를 삭제한다.
     */
    @Override
    public void write(Chunk<? extends UUID> chunk) {
        for (UUID userId : chunk) {
            localCredentialRepository.deleteByUserId(userId);
            sellerProfileRepository.deleteByUserId(userId);
            userRepository.deleteById(userId);
        }
    }
}
