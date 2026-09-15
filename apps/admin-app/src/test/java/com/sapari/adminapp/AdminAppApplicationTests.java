package com.sapari.adminapp;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
// 실제 컨텍스트를 띄운다. CI 에는 application*.yaml 이 없어 부팅할 수 없으므로
// 이 태그로 걸러진다(루트 build.gradle). 로컬에서는 그대로 돈다.
@Tag("context")
class AdminAppApplicationTests {

    @Test
    void contextLoads() {
    }

}
