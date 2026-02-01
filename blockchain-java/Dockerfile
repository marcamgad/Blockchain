FROM maven:3.9-eclipse-temurin-17-alpine AS build

WORKDIR /build

# Copy pom.xml first for better layer caching
COPY pom.xml .

# Download dependencies (cached if pom.xml unchanged)
RUN mvn dependency:go-offline -B

# Copy source code
COPY src ./src

# Build the application
RUN mvn clean package -DskipTests -B

# ============================================
# Runtime stage
# ============================================
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Install curl for health checks and libstdc++ for LevelDB JNI
RUN apk add --no-cache curl libstdc++

# Create non-root user for security
RUN addgroup -S blockchain && adduser -S blockchain -G blockchain

# Create data directory with proper permissions
RUN mkdir -p /app/data && chown -R blockchain:blockchain /app

# Copy the Spring Boot fat JAR from build stage
COPY --from=build --chown=blockchain:blockchain /build/target/blockchain-java-1.0-SNAPSHOT.jar ./blockchain.jar

# Switch to non-root user
USER blockchain

# Environment variables with defaults
ENV API_PORT=8000 \
    SERVER_PORT=8000 \
    P2P_PORT=6001 \
    STORAGE_PATH=/app/data \
    NETWORK_ID=101 \
    PROTOCOL_VERSION=1.0.0 \
    JAVA_OPTS="-Xmx2G -Xms512M"

# Expose API and P2P ports
EXPOSE ${API_PORT}
EXPOSE ${P2P_PORT}

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:${API_PORT}/api/v1/health || exit 1

# Entry point with JVM options
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar blockchain.jar"]
