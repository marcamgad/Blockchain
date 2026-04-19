#!/usr/bin/env bash
echo "Stopping and removing all 20 containers..."
docker compose down -v
echo "Removing Docker image..."
docker rmi hybridchain:latest 2>/dev/null || true
echo "Cluster torn down. Storage volumes deleted."
