# ---------- build stage ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Use the repo's Maven settings (mirror config) if present
COPY .mvn/settings.xml /root/.m2/settings.xml

# Cache dependencies first
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q -DskipTests package

# ---------- runtime stage ----------
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

RUN groupadd --system ocr && useradd --system --gid ocr ocr
USER ocr

COPY --from=build /build/target/*.jar /app/app.jar

# All runtime configuration is via environment variables (see .env / docker-compose.yml):
#   OLLAMA_BASE_URL, OLLAMA_MODEL, OLLAMA_IDLE_TIMEOUT, OLLAMA_REQUEST_TIMEOUT,
#   OLLAMA_KEEP_ALIVE, OLLAMA_NUM_CTX, OCR_RENDER_DPI, OCR_MAX_IMAGE_DIMENSION,
#   CALLBACK_AUTH_TOKEN_URL, CALLBACK_AUTH_CLIENT_ID, CALLBACK_AUTH_CLIENT_SECRET,
#   CALLBACK_AUTH_SCOPE, SERVER_PORT, MAX_FILE_SIZE, JAVA_OPTS
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
