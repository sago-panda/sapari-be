package com.sapari.batchapp.user.withdrawal;

import lombok.RequiredArgsConstructor;

import java.util.UUID;

import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.stereotype.Component;

import com.sapari.user.domain.repository.WithdrawnUserRetentionRepository;

@Component
@RequiredArgsConstructor
public class UserWithdrawalRetentionPurgeWriter implements ItemWriter<UUID> {

    private final WithdrawnUserRetentionRepository withdrawnUserRetentionRepository;

    @Override
    public void write(Chunk<? extends UUID> chunk) {
        for (UUID originalUserId : chunk) {
            // 추후 다른 도메인 보존정보 삭제가 추가되면 이 row는 마지막에 삭제한다.
            withdrawnUserRetentionRepository.deleteByOriginalUserId(originalUserId);
        }
    }
}
