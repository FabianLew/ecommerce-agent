# Backend image (ADR-0023): one image, the process role is chosen with SPRING_PROFILES_ACTIVE (api or worker).
# Build from the repository root, because the build packs /knowledge into the jar:
#   docker build -f backend/Dockerfile -t operator-backend .
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY knowledge knowledge
COPY backend backend
WORKDIR /src/backend
RUN ./gradlew --no-daemon bootJar -x test && cp build/libs/*.jar /app.jar

FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app --home /app app
WORKDIR /app
COPY --from=build /app.jar /app/app.jar
USER app
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
