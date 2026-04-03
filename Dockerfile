# Use non-Alpine for builder (gRPC protoc plugin requires glibc)
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /app

# Copy build files
COPY settings.gradle.kts .
COPY build.gradle.kts .
COPY gradlew .
COPY gradle/ gradle/
COPY proto/ proto/
COPY server/ server/

# Build the application
RUN chmod +x gradlew && ./gradlew :server:bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

RUN addgroup -S rateforge && adduser -S rateforge -G rateforge

COPY --from=builder /app/server/build/libs/*.jar app.jar

RUN chown rateforge:rateforge app.jar

USER rateforge

EXPOSE 9090

ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-XX:+ExitOnOutOfMemoryError", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "app.jar"]
