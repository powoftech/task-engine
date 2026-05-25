FROM maven:3.9-eclipse-temurin-25-alpine AS builder

WORKDIR /app

COPY api-gateway/pom.xml .
COPY api-gateway/src ./src
COPY contracts ./contracts

RUN mvn clean package -DskipTests

FROM eclipse-temurin:25-jre-alpine

WORKDIR /app

COPY --from=builder /app/target/*.jar app.jar
COPY --from=builder /app/contracts ./contracts

EXPOSE 8080

RUN apk add --no-cache curl \
    && addgroup -S gatewaygroup \
    && adduser -S gatewayuser -G gatewaygroup
USER gatewayuser

ENTRYPOINT ["java", "-jar", "app.jar"]
