# ─── Stage 1: Build ────────────────────────────────────────────────────────
FROM maven:3.9.9-eclipse-temurin-21-alpine AS build

WORKDIR /app

# Copy only pom first for caching
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

# Copy source
COPY src ./src

# Build jar
RUN mvn -B -q clean package -DskipTests

# ─── Stage 2: Runtime ──────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

# ✅ Fix timezone issue permanently
ENV TZ=UTC
ENV JAVA_TOOL_OPTIONS="-Duser.timezone=UTC"

# Install required conversion tools
RUN apk add --no-cache \
    ffmpeg \
    imagemagick \
    libheif \
    curl

# Allow ImageMagick PDF processing
RUN if [ -f /etc/ImageMagick-7/policy.xml ]; then \
      sed -i 's/rights="none" pattern="PDF"/rights="read|write" pattern="PDF"/' \
      /etc/ImageMagick-7/policy.xml ; \
    fi

# Create non-root user
RUN addgroup -S fileconvert && adduser -S fileconvert -G fileconvert

WORKDIR /app

# Create required directories
RUN mkdir -p /app/uploads /app/outputs && \
    chown -R fileconvert:fileconvert /app

# Copy exact jar name (safer for production)
COPY --from=build /app/target/converter-1.0.0.jar app.jar
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