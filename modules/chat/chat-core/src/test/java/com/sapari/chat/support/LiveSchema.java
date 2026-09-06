package com.sapari.chat.support;

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
 * 테스트 DB에 {@code live_schema}를 운영과 같은 마이그레이션으로 세운다.
 *
 * <p><b>왜 한 곳에 두는가.</b> 세 테스트가 각자 {@code V1__init_live.sql} 한 파일만 읽고 있었다. 그
 * 상태에서 마이그레이션을 새로 더하면 그 변경은 <b>어떤 테스트에도 적용되지 않은 채</b> 초록불을 받는다 —
 * 스키마를 바꿔 놓고 옛 스키마로 검증하는 셈이라, 제약을 더해도 그 제약이 도는지 알 수 없다.
 * 규칙("전부, 순서대로")이 세 곳에 흩어져 있으면 네 번째 테스트가 또 한 파일만 읽는다.
 *
 * <p>정렬은 Flyway와 같은 기준이다 — {@code V}와 {@code __} 사이를 <b>수로</b> 비교한다. 문자열로
 * 비교하면 {@code V202609061437}이 {@code V1}보다 앞서서, 아직 없는 테이블에 인덱스를 만들려다 깨진다.
 *
 * <p>Flyway 자체를 돌리지 않는 것은 이 모듈에 Flyway 의존이 없기 때문이다. 대신 같은 파일을 같은
 * 순서로 실행한다 — 검증 대상은 러너가 아니라 SQL이다.
 */
public final class LiveSchema {

    private static final String MIGRATION_DIR = "db/migration/live";

    private LiveSchema() {
    }

    public static void applyTo(String jdbcUrl, String username, String password) throws SQLException, IOException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
                Statement statement = connection.createStatement()) {
            for (Path migration : migrations()) {
                statement.execute(Files.readString(migration));
            }
        }
    }

    private static List<Path> migrations() throws IOException {
        try (Stream<Path> files = Files.list(repositoryRoot().resolve(MIGRATION_DIR))) {
            return files.filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .sorted(Comparator.comparingLong(LiveSchema::version))
                    .toList();
        }
    }

    /** {@code V<version>__<설명>.sql}의 version. 이름이 그 꼴이 아니면 실행 순서를 정할 수 없으므로 멈춘다. */
    private static long version(Path migration) {
        String name = migration.getFileName().toString();
        int end = name.indexOf("__");
        if (!name.startsWith("V") || end < 0) {
            throw new IllegalStateException("마이그레이션 이름이 V<version>__<설명>.sql 이 아니다 — " + name);
        }
        return Long.parseLong(name.substring(1, end));
    }

    /** 테스트 작업 디렉터리는 모듈이라 저장소 루트까지 올라간다 — settings.gradle이 그 표지다. */
    private static Path repositoryRoot() {
        Path here = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path candidate = here; candidate != null; candidate = candidate.getParent()) {
            if (Files.exists(candidate.resolve("settings.gradle"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("저장소 루트를 찾지 못했다 — 시작 위치=" + here);
    }
}
