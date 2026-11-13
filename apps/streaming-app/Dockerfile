# =============================================================================
# streaming-app 이미지
#
# 빌드 (리포 루트에서 — 멀티 모듈이라 컨텍스트가 루트여야 함):
#   docker build -f apps/streaming-app/Dockerfile -t sapari-streaming-app:<tag> .
#
# JDK: Azul Zulu 21.0.13.0.3 — 2026-10-21(CVE-2026-91403)·10-24(CVE-2026-114820) OpenJDK 보안
#      권고를 반영한 업스트림 빌드. 태그는 재빌드되면 내용이 바뀌므로 다이제스트로 고정한다.
#      올릴 때는 두 단계의 버전·다이제스트를 같이 바꾼다 (live-app Dockerfile 과 같이).
# =============================================================================
# 빌드 단계는 러너 아키텍처($BUILDPLATFORM)에서 네이티브로 돈다. jar 는 아키텍처와 무관하므로
# arm64 이미지를 만들 때도 gradle 을 에뮬레이션으로 돌리지 않는다.
FROM --platform=$BUILDPLATFORM azul/zulu-openjdk:21.0.13.0.3-jdk@sha256:92a8e15ca70bccda8f6c30c8825bfccd355ec30678b8bd4b0623527a05985e97 AS build
WORKDIR /src
COPY . .
RUN ./gradlew :apps:streaming-app:bootJar --no-daemon \
 && java -Djarmode=tools -jar apps/streaming-app/build/libs/streaming-app-0.0.1-SNAPSHOT.jar \
      extract --layers --launcher --destination /extracted

FROM azul/zulu-openjdk:21.0.13.0.3-21.54-jre-headless@sha256:e8d287f63af0623bc03b6d8ea134fd35721865885f08cb5be82df56148682d5b
# 실행 단계에는 RUN 을 두지 않는다. 대상 아키텍처(arm64)에서 명령을 돌리려면 러너에 QEMU 가
# 필요해지기 때문이다. 사용자는 만들지 않고 숫자 UID 로만 지정한다(홈 디렉터리가 필요 없다).
WORKDIR /app

# 바뀌는 빈도가 낮은 층부터 — 코드만 바뀌면 마지막 층만 다시 받는다.
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./

# 설정은 템플릿을 그대로 싣는다 — 값은 전부 환경변수 자리표시자라 비밀이 없고, REQUIRED 키는
# 기본값이 비어 있어 주입이 빠지면 부팅에서 죽는다. 배포가 /app/config 를 마운트하면 그쪽이 이긴다.
COPY apps/streaming-app/src/main/resources/application.yml.example config/application.yml

USER 10001:10001
# 8080 사용자 트래픽(WS, server.port 미설정 = Boot 기본), 8092 management(메트릭)
#   — infra/AGENTS.md "Observability ports (streaming-app)"
EXPOSE 8080 8092
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
