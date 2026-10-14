FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q clean package -DskipTests

FROM eclipse-temurin:21-jre-noble
ENV TZ=UTC JAVA_TOOL_OPTIONS="-Duser.timezone=UTC -Djava.awt.headless=true" IMAGEMAGICK_BINARY=convert
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    ffmpeg imagemagick libheif-plugin-libde265 \
    libreoffice-writer libreoffice-calc libreoffice-impress libreoffice-draw \
    fonts-noto-core fonts-noto-cjk fonts-liberation2 icc-profiles-free fontconfig curl \
    && fc-cache -f && rm -rf /var/lib/apt/lists/*
RUN test -f /usr/share/color/icc/sRGB.icc
RUN groupadd --system fileconvert && useradd --system --gid fileconvert --create-home fileconvert
WORKDIR /app
RUN mkdir -p /app/uploads /app/outputs && chown -R fileconvert:fileconvert /app
COPY --from=build --chown=fileconvert:fileconvert /app/target/converter-1.0.0.jar app.jar
USER fileconvert
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=30s --start-period=90s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-Xmx768m", "-Xms128m", "-XX:+UseG1GC", "-XX:+UseContainerSupport", "-jar", "app.jar"]
