# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace

# Use the repository's Gradle Wrapper (9.8.1), not a separate Gradle image.
COPY demo/gradlew demo/build.gradle demo/settings.gradle ./
COPY demo/gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon --version

COPY demo/src ./src
# develop's authentication tests read ../db/init from the Gradle project root.
COPY db/init /db/init

# Run tests by default. Packaging only: --build-arg RUN_TESTS=false.
ARG RUN_TESTS=true
RUN case "$RUN_TESTS" in \
      true) ./gradlew --no-daemon clean build ;; \
      false) ./gradlew --no-daemon clean bootJar -x test ;; \
      *) echo 'RUN_TESTS must be true or false' >&2; exit 1 ;; \
    esac \
    && find build/libs -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' \
       -exec cp {} /workspace/app.jar \; \
    && test -s /workspace/app.jar

FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app
RUN command -v curl \
    && groupadd --gid 10001 app \
    && useradd --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app

COPY --from=build --chown=10001:10001 /workspace/app.jar /app/app.jar
COPY --chown=10001:10001 db/docs /db/docs
USER 10001:10001

# Metadata only; application.yml reads PORT (default: 8080).
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=3 \
    CMD curl --fail --silent --show-error --max-time 2 --noproxy '*' "http://127.0.0.1:${PORT:-8080}/health" || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
