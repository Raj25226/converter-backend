# ---------- BUILD STAGE ----------
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app

COPY pom.xml .
RUN mvn -q -e -B dependency:go-offline

COPY src ./src
RUN mvn -q -DskipTests package

# ---------- RUNTIME STAGE ----------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# 🔒 Fix PostgreSQL timezone handshake issue
ENV TZ=UTC
ENV JAVA_TOOL_OPTIONS="-Duser.timezone=UTC"

COPY --from=build /app/target/converter-0.0.1.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]