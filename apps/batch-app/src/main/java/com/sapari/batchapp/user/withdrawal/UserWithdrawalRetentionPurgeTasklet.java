package com.sapari.batchapp.user.withdrawal;

import lombok.RequiredArgsConstructor;

import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

import com.sapari.global.time.TimeProvider;
import com.sapari.user.domain.repository.WithdrawnUserRetentionRepository;

@Component
@RequiredArgsConstructor
public class UserWithdrawalRetentionPurgeTasklet implements Tasklet {

    private final WithdrawnUserRetentionRepository withdrawnUserRetentionRepository;
    private final TimeProvider timeProvider;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        withdrawnUserRetentionRepository.deleteExpiredBefore(timeProvider.now());
        return RepeatStatus.FINISHED;
    }
}
