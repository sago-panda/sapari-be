# =============================================================================
# live-app 이미지
#
# 빌드 (리포 루트에서 — 멀티 모듈이라 컨텍스트가 루트여야 함):
#   docker build -f apps/live-app/Dockerfile -t sapari-live-app:<tag> .
#
# JDK: Azul Zulu 21.0.13.0.3 — 2026-10-21(CVE-2026-91403)·10-24(CVE-2026-114820) OpenJDK 보안
#      권고를 반영한 업스트림 빌드. 태그는 재빌드되면 내용이 바뀌므로 다이제스트로 고정한다.
#      올릴 때는 두 단계의 버전·다이제스트를 같이 바꾼다.
# =============================================================================
FROM azul/zulu-openjdk:21.0.13.0.3-jdk@sha256:92a8e15ca70bccda8f6c30c8825bfccd355ec30678b8bd4b0623527a05985e97 AS build
WORKDIR /src
COPY . .
RUN ./gradlew :apps:live-app:bootJar --no-daemon \
 && java -Djarmode=tools -jar apps/live-app/build/libs/live-app-0.0.1-SNAPSHOT.jar \
      extract --layers --launcher --destination /extracted

FROM azul/zulu-openjdk:21.0.13.0.3-21.54-jre-headless@sha256:e8d287f63af0623bc03b6d8ea134fd35721865885f08cb5be82df56148682d5b
RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app

# 바뀌는 빈도가 낮은 층부터 — 코드만 바뀌면 마지막 층만 다시 받는다.
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./

# 설정은 템플릿을 그대로 싣는다 — 값은 전부 환경변수 자리표시자라 비밀이 없고, REQUIRED 키는
# 기본값이 비어 있어 주입이 빠지면 부팅에서 죽는다. 배포가 /app/config 를 마운트하면 그쪽이 이긴다.
COPY apps/live-app/src/main/resources/application.yaml.example config/application.yaml

USER app
# 8081 사용자 트래픽, 8091 management(메트릭) — infra/AGENTS.md "Observability ports (live-app)"
EXPOSE 8081 8091
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
