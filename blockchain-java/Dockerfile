FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# Copy the pre-built Assembly fat JAR from the host
COPY target/*-jar-with-dependencies.jar app.jar

# Create data directory for LevelDB storage and install curl for healthchecks
RUN apt-get update && apt-get install -y curl && \
    mkdir -p /app/data /app/logs && \
    rm -rf /var/lib/apt/lists/*

# Expose P2P port and REST API port
EXPOSE 6001 8000

# JVM flags: simpler -jar execution
ENTRYPOINT ["java", \
  "-Xmx512m", \
  "-Xms128m", \
  "-XX:+UseG1GC", \
  "-Dfile.encoding=UTF-8", \
  "-jar", "app.jar"]
