package com.sapari.live.application.service;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sapari.live.domain.model.RecentView;
import com.sapari.live.domain.repository.RecentViewRepository;

/**
 * 최근 본 기록 한 건 쓰기의 트랜잭션 경계. 기록기({@code AsyncRecentViewRecorder})의 워커 스레드가 부른다 —
 * 입장 요청 스레드는 여기 오지 않는다. 경계를 application 에 두는 것은 {@code @Transactional} 배치 규칙(ArchUnit)이다.
 */
@Service
@RequiredArgsConstructor
public class RecentViewWriter {

    private final RecentViewRepository recentViewRepository;

    @Transactional
    public void write(RecentView view) {
        recentViewRepository.upsert(view);
    }
}
