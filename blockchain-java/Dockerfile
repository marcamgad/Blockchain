FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# Copy the Spring Boot executable JAR from the host. The *-jar-with-dependencies.jar is NOT runnable:
# the assembly bundles the Boot-repackaged jar, so com.hybrid.App ends up under BOOT-INF/ and the JVM
# cannot find it (ClassNotFoundException at start).
COPY target/blockchain-java-*-SNAPSHOT.jar app.jar

# Create data directory for LevelDB storage and install curl for healthchecks
RUN apt-get update && apt-get install -y curl && \
    mkdir -p /app/data /app/logs && \
    rm -rf /var/lib/apt/lists/*

# [HC-076] do not run the node as root: a compromise of the JVM would otherwise own the container
RUN groupadd --system hybrid && useradd --system --gid hybrid --home-dir /app --shell /usr/sbin/nologin hybrid && \
    chown -R hybrid:hybrid /app
USER hybrid

# Expose P2P port and REST API port
EXPOSE 6001 8000

# JVM flags: simpler -jar execution
ENTRYPOINT ["java", \
  "-Xmx512m", \
  "-Xms128m", \
  "-XX:+UseG1GC", \
  "-Dfile.encoding=UTF-8", \
  "-jar", "app.jar"]
