package com.sapari.live.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("VodStatus — 앞으로만 간다, 끝 상태는 바뀌지 않는다")
class VodStatusTest {

    @Test
    @DisplayName("처리 중은 없음에서만 온다")
    void processing() {
        assertThat(VodStatus.PROCESSING.predecessors()).containsExactlyInAnyOrder(VodStatus.NONE);
    }

    @Test
    @DisplayName("녹화 완료·실패는 없음·처리 중에서 온다 — 시작 알림을 놓쳐도 끝 상태에 도달한다")
    void terminals() {
        assertThat(VodStatus.READY.predecessors()).containsExactlyInAnyOrder(VodStatus.NONE, VodStatus.PROCESSING);
        assertThat(VodStatus.FAILED.predecessors()).containsExactlyInAnyOrder(VodStatus.NONE, VodStatus.PROCESSING);
    }

    @Test
    @DisplayName("없음으로 돌아가는 전이는 없다, 자기 자신도 앞 단계가 아니다")
    void noBackwardOrSelf() {
        assertThat(VodStatus.NONE.predecessors()).isEmpty();
        for (VodStatus s : VodStatus.values()) {
            assertThat(s.predecessors()).doesNotContain(s, VodStatus.READY, VodStatus.FAILED);
        }
    }
}
