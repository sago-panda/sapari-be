package com.sapari.batchapp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * ⚠️ <b>이 앱에는 컨텍스트 가드가 없다.</b> 이름이 스프링 관례({@code *ApplicationTests})라 "부팅을
 * 검증한다" 로 읽히는데, 아래 단언은 클래스 리터럴이 {@code null} 이 아닌지만 본다 — 항상 참이다.
 *
 * <p><b>실측</b>: 앱별로 컨텍스트를 실제로 띄우는 테스트 수는 streaming-app 4 · live-app 3 ·
 * admin-app 1 · <b>api-app 0 · batch-app 0</b> 이다. 이 앱의 나머지 다섯 테스트는 전부 컨텍스트를
 * 띄우지 않는다(셋은 Mockito 단위, 둘은 프로퍼티 바인딩). 그래서 batch-app 의 배선·설정 누락은
 * <b>CI 에서 아무것도 걸리지 않는다</b>.
 *
 * <p>여기에 적어 두는 이유는 다음 사람이 이 파일을 보고 "batch-app 은 부팅이 검증된다" 고 믿지 않게
 * 하는 것이다. 진짜 가드가 필요하면 {@code @SpringBootTest} + Testcontainers Postgres 가 필요하고
 * (정산 앱이라 관계형 DB 를 쓴다), 그건 이 파일 한 줄이 아니라 별 작업이다 — api-app 도 같은
 * 처지라 둘을 함께 보는 쪽이 맞다.
 */
class BatchAppApplicationTests {

    @Test
    void applicationClassExists() {
        assertThat(BatchAppApplication.class).isNotNull();
    }
}
