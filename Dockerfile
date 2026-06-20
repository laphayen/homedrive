FROM eclipse-temurin:17-jdk-jammy AS builder

WORKDIR /workspace
COPY gradlew build.gradle settings.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon

COPY src ./src
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:17-jre-jammy

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system homedrive \
    && useradd --system --gid homedrive --create-home homedrive
WORKDIR /app
COPY --from=builder /workspace/build/libs/*.jar app.jar
RUN mkdir -p /data/uploads /data/chunks && chown -R homedrive:homedrive /app /data

USER homedrive
EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
