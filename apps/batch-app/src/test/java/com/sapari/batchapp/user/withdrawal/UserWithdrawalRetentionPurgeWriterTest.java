package com.sapari.batchapp.user.withdrawal;

import static org.mockito.Mockito.inOrder;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.infrastructure.item.Chunk;

import com.sapari.user.domain.repository.WithdrawnUserRetentionRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserWithdrawalRetentionPurgeWriter 테스트")
class UserWithdrawalRetentionPurgeWriterTest {

    @Mock
    private WithdrawnUserRetentionRepository withdrawnUserRetentionRepository;

    @Test
    @DisplayName("만료된 탈퇴회원 보존 row를 originalUserId 기준으로 삭제한다")
    void writeDeletesExpiredRetentionsByOriginalUserId() throws Exception {
        // given
        UUID firstUserId = UUID.randomUUID();
        UUID secondUserId = UUID.randomUUID();
        UserWithdrawalRetentionPurgeWriter writer = new UserWithdrawalRetentionPurgeWriter(
                withdrawnUserRetentionRepository
        );

        // when
        writer.write(new Chunk<>(List.of(firstUserId, secondUserId)));

        // then
        InOrder inOrder = inOrder(withdrawnUserRetentionRepository);
        inOrder.verify(withdrawnUserRetentionRepository).deleteByOriginalUserId(firstUserId);
        inOrder.verify(withdrawnUserRetentionRepository).deleteByOriginalUserId(secondUserId);
    }
}
