# Build Stage
# build context: ragvault/ 루트 (core/ 포함을 위해)
# Usage: docker build -f app-internal/Dockerfile .
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace

# Gradle wrapper (app-internal 소유 — composite build로 core도 빌드)
COPY app-internal/gradlew app-internal/gradlew
COPY app-internal/gradle app-internal/gradle
RUN chmod +x app-internal/gradlew

# 의존성 레이어 캐시: build.gradle + settings.gradle 먼저
COPY core/build.gradle core/settings.gradle core/
COPY app-internal/build.gradle app-internal/settings.gradle app-internal/
RUN cd app-internal && ./gradlew dependencies --no-daemon --quiet || true

# 소스 복사 후 빌드
COPY core/src core/src
COPY app-internal/src app-internal/src
RUN cd app-internal && ./gradlew clean bootJar -x test --no-daemon

# Run Stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# PDF OCR 폴백(PdfOcrFallbackService) + TesseractOcrService 용
RUN apk add --no-cache tesseract-ocr tesseract-ocr-data-eng tesseract-ocr-data-kor
ENV TESSERACT_DATAPATH=/usr/share/tessdata

RUN addgroup -S raggroup && adduser -S raguser -G raggroup && \
    mkdir -p /app/knowledge-internal && \
    chown raguser:raggroup /app/knowledge-internal
USER raguser

COPY --from=builder /workspace/app-internal/build/libs/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "app.jar"]
