# Stage 1: Build the backend
FROM gradle:8.10.2-jdk17 AS build
COPY --chown=gradle:gradle . /home/gradle/src
WORKDIR /home/gradle/src
RUN gradle :backend:buildFatJar --no-daemon

# Stage 2: Run the application
# Using Eclipse Temurin for better stability and smaller image size
FROM eclipse-temurin:17-jre-alpine
EXPOSE 8080
RUN mkdir /app
COPY --from=build /home/gradle/src/backend/build/libs/*.jar /app/medtrack-backend.jar
ENTRYPOINT ["java", "-jar", "/app/medtrack-backend.jar"]
