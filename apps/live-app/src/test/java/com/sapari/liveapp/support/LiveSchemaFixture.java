package com.sapari.liveapp.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 테스트 DB 에 {@code live_schema} 를 운영 마이그레이션 파일 그대로 세운다.
 *
 * <p>⚠️ <b>{@link LiveAppContextFixture} 의 {@code spring.flyway.*} 는 지금 아무것도 하지 않는다.</b>
 * Spring Boot 4 에서 Flyway 자동설정은 {@code spring-boot-flyway} 모듈로 분리됐고, 이 앱의 테스트
 * 클래스패스에는 {@code flyway-core} 만 있다(실측: 컨텍스트가 뜬 뒤 {@code live_schema.live_rooms} 가 없다).
 * 테이블을 쓰지 않는 테스트만 있던 동안에는 드러나지 않았다. 의존을 더해 고치면 기존 컨텍스트 테스트
 * 전부가 마이그레이션을 돌기 시작하므로, 그 변경은 이 작업(SPR-157) 밖에 둔다 — 테이블이 필요한 테스트만
 * 이 픽스처를 부른다.
 *
 * <p>순서는 Flyway 와 같다 — {@code V} 와 {@code __} 사이를 수로 비교한다(chat-core {@code LiveSchema} 와 같은 규칙).
 */
public final class LiveSchemaFixture {

    private LiveSchemaFixture() {
    }

    public static void apply(String jdbcUrl, String username, String password) throws SQLException, IOException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS live_schema");
            for (Path migration : migrations()) {
                statement.execute(Files.readString(migration));
            }
        }
    }

    private static List<Path> migrations() throws IOException {
        Path dir = Path.of("..", "..", "db", "migration", "live").toAbsolutePath().normalize();
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .sorted(Comparator.comparingLong(LiveSchemaFixture::version))
                    .toList();
        }
    }

    private static long version(Path migration) {
        String name = migration.getFileName().toString();
        int end = name.indexOf("__");
        if (!name.startsWith("V") || end < 0) {
            throw new IllegalStateException("마이그레이션 이름이 V<version>__<설명>.sql 이 아니다 — " + name);
        }
        return Long.parseLong(name.substring(1, end));
    }
}
