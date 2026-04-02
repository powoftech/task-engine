FROM maven:3.9-eclipse-temurin-25-alpine AS builder

WORKDIR /app

COPY pom.xml .
COPY src ./src

RUN mvn clean package -DskipTests

FROM eclipse-temurin:25-jre-alpine

WORKDIR /app

COPY --from=builder /app/target/*.jar app.jar

EXPOSE 8080

RUN addgroup -S gatewaygroup && adduser -S gatewayuser -G gatewaygroup
USER gatewayuser

ENTRYPOINT ["java", "-jar", "app.jar"]