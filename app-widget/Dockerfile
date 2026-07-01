# Build Stage
# build context: ragvault/ 루트 (core/ 포함을 위해)
# Usage: docker build -f app-widget/Dockerfile .
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace

# Gradle wrapper (app-widget 소유 — composite build로 core도 빌드)
COPY app-widget/gradlew app-widget/gradlew
COPY app-widget/gradle app-widget/gradle
RUN chmod +x app-widget/gradlew

# 의존성 레이어 캐시: build.gradle + settings.gradle 먼저
COPY core/build.gradle core/settings.gradle core/
COPY app-widget/build.gradle app-widget/settings.gradle app-widget/
RUN cd app-widget && ./gradlew dependencies --no-daemon --quiet || true

# 소스 복사 후 빌드
COPY core/src core/src
COPY app-widget/src app-widget/src
RUN cd app-widget && ./gradlew bootJar --no-daemon -x test -q

# Run Stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

COPY --from=builder /workspace/app-widget/build/libs/*.jar app.jar
RUN mkdir -p knowledge

EXPOSE 8081

ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "app.jar"]
