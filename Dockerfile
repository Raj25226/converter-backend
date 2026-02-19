# ─── Stage 1: Build ────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /app

# Copy Maven wrapper and POM first (layer caching)
COPY pom.xml .
RUN apk add --no-cache maven && \
    mvn dependency:go-offline -B --no-transfer-progress

# Copy source
COPY src ./src

# Build
RUN mvn clean package -DskipTests --no-transfer-progress

# ─── Stage 2: Runtime ──────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

# Install system dependencies for file conversion
RUN apk add --no-cache \
    ffmpeg \
    imagemagick \
    libheif \
    curl \
    && rm -rf /var/cache/apk/*

# Allow ImageMagick to process PDFs
RUN sed -i 's/rights="none" pattern="PDF"/rights="read|write" pattern="PDF"/' \
    /etc/ImageMagick-7/policy.xml 2>/dev/null || true

# Create non-root user
RUN addgroup -S fileconvert && adduser -S fileconvert -G fileconvert

WORKDIR /app

# Create directories
RUN mkdir -p /app/uploads /app/outputs && \
    chown -R fileconvert:fileconvert /app

# Copy JAR
COPY --from=build /app/target/*.jar app.jar
RUN chown fileconvert:fileconvert app.jar

USER fileconvert

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/api/analytics/stats || exit 1

ENTRYPOINT ["java", \
    "-Xmx512m", \
    "-Xms256m", \
    "-XX:+UseG1GC", \
    "-XX:+UseContainerSupport", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "app.jar"]