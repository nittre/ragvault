# Build Stage
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app

# Gradle wrapper 복사 (캐시 최적화)
COPY gradlew .
COPY gradle gradle
RUN chmod +x gradlew

# 의존성 먼저 다운로드 (캐시 레이어)
COPY build.gradle settings.gradle ./
RUN ./gradlew dependencies --no-daemon --quiet || true

# 소스 빌드 (테스트 제외)
COPY src src
RUN ./gradlew clean build -x test --no-daemon

# Run Stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# 보안: non-root 사용자
RUN addgroup -S raggroup && adduser -S raguser -G raggroup
USER raguser

COPY --from=builder /app/build/libs/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "app.jar"]
