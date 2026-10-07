FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S marketplace && adduser -S marketplace -G marketplace
COPY --from=build /app/target/marketplace-1.0.0.jar app.jar
USER marketplace
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
