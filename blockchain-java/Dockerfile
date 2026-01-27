# Build stage
FROM maven:3.8.4-openjdk-17-slim AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# Run stage
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Create a non-root user for better security
RUN addgroup -S blockchain && adduser -S blockchain -G blockchain
USER blockchain

# Copy the fat jar from build stage
COPY --from=build /app/target/blockchain-java-1.0-SNAPSHOT.jar ./blockchain.jar

# Standard ports
EXPOSE 8000
EXPOSE 6001

# Entry point starts the node
ENTRYPOINT ["java", "-jar", "blockchain.jar"]
