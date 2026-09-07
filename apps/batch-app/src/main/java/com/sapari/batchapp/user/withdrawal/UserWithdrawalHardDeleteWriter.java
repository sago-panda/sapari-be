package com.sapari.batchapp.user.withdrawal;

import lombok.RequiredArgsConstructor;

import java.util.UUID;

import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.stereotype.Component;

import com.sapari.seller.domain.repository.LocalCredentialRepository;
import com.sapari.seller.domain.repository.SellerProfileRepository;
import com.sapari.user.domain.repository.UserRepository;
import com.sapari.user.model.UserStatus;

@Component
@RequiredArgsConstructor
public class UserWithdrawalHardDeleteWriter implements ItemWriter<UUID> {

    private final LocalCredentialRepository localCredentialRepository;
    private final SellerProfileRepository sellerProfileRepository;
    private final UserRepository userRepository;

    /**
     * 탈퇴 유예 기간이 끝난 사용자와 직접 연결된 인증·판매자 프로필 데이터를 삭제한 뒤 사용자 row를 삭제한다.
     * 트랜잭션은 Batch Step의 chunk 경계를 사용하며 repository가 트랜잭션 참여를 확인한다.
     */
    @Override
    public void write(Chunk<? extends UUID> chunk) {
        for (UUID userId : chunk) {
            // reader 이후 상태 변경·중복 실행과 경합하므로 사용자부터 잠근 뒤 하위 데이터를 삭제한다.
            var user = userRepository.findByIdForUpdate(userId);
            if (user.isEmpty() || user.get().status() != UserStatus.WITHDRAWING) {
                continue;
            }
            localCredentialRepository.deleteByUserId(userId);
            sellerProfileRepository.deleteByUserId(userId);
            userRepository.deleteById(userId);
        }
    }
}
