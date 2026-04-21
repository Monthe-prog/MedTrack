# Stage 1: Build the backend
FROM gradle:8.5-jdk17 AS build
COPY --chown=gradle:gradle . /home/gradle/src
WORKDIR /home/gradle/src/backend
RUN gradle buildFatJar --no-daemon

# Stage 2: Run the application
FROM openjdk:17-slim
EXPOSE 8080
RUN mkdir /app
# We look into the backend/build folder specifically
COPY --from=build /home/gradle/src/backend/build/libs/*.jar /app/medtrack-backend.jar
ENTRYPOINT ["java", "-jar", "/app/medtrack-backend.jar"]
