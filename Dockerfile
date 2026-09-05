# syntax=docker/dockerfile:1.7
FROM maven:3.9.11-eclipse-temurin-21-alpine AS build
WORKDIR /workspace
COPY pom.xml ./
COPY src ./src
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S campusguard && adduser -S -G campusguard -u 10001 campusguard \
    && mkdir -p /data/media && chown -R campusguard:campusguard /data
WORKDIR /app
COPY --from=build /workspace/target/campusguard-backend-*.jar app.jar
USER 10001
EXPOSE 8080 9090
ENV JAVA_TOOL_OPTIONS="-Djava.awt.headless=true -XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
