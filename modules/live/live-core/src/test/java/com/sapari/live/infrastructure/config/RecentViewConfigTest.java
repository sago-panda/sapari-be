package com.sapari.live.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import com.sapari.live.application.port.LiveMetrics;
import com.sapari.live.application.port.RecentViewDropReason;
import com.sapari.live.application.port.RecentViewRecorder;
import com.sapari.live.application.port.RecordingLiveMetrics;
import com.sapari.live.application.service.RecentViewWriter;
import com.sapari.live.domain.repository.RecentViewRepository;

/**
 * 실행기가 쓰기 경로(writer → 저장소 → EMF → DataSource)보다 <b>먼저</b> 닫히는가. 거꾸로면 배포마다 남은 쓰기가 닫힌 풀에서
 * {@code FAILED} 로 세어져 DB 장애처럼 보인다.
 *
 * <p>재는 법: writer 가 소멸되는 순간 기록을 하나 넣어 본다. 실행기가 이미 닫혔으면 제출이 거부돼 {@code REJECTED} 로 세어진다.
 * writer 를 팩토리 메서드 인자로만 받던 배선으로 되돌리면 이 테스트가 깨진다(확인함).
 */
@DisplayName("최근 본 기록 설정 — 실행기가 쓰기 경로보다 먼저 닫힌다")
class RecentViewConfigTest {

    private static final AtomicReference<RecentViewRecorder> RECORDER = new AtomicReference<>();

    /** 소멸 시점에 기록을 시도하는 writer. */
    static class ProbeWriter extends RecentViewWriter implements DisposableBean {
        ProbeWriter() {
            super(mock(RecentViewRepository.class));
        }

        @Override
        public void destroy() {
            RECORDER.get().record(UUID.randomUUID(), UUID.randomUUID(), Instant.EPOCH);
        }
    }

    @Test
    @DisplayName("writer 소멸 시점에 실행기는 이미 닫혀 있다")
    void executorClosesBeforeWriter() {
        RecordingLiveMetrics metrics = new RecordingLiveMetrics();

        // 설정을 먼저 등록한다 — 소멸은 등록 역순이라, 의존이 없으면 writer 가 먼저 소멸된다(운영에서 DataSource·EMF 가
        // 컴포넌트 스캔 뒤 자동 설정으로 등록돼 먼저 닫히는 것과 같은 모양). ApplicationContextRunner 는 사용자 설정을
        // 마지막에 등록해 이 순서를 만들 수 없다.
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(RecentViewConfig.class);
            context.registerBean(LiveMetrics.class, () -> metrics);
            context.registerBean(RecentViewWriter.class, ProbeWriter::new);
            context.refresh();
            RECORDER.set(context.getBean(RecentViewRecorder.class));
        }

        assertThat(metrics.recentViewDrops).containsExactly(RecentViewDropReason.REJECTED);
    }
}
