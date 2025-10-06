#!/bin/bash
# Start Jaeger all-in-one for local development (Podman)
# Exposes:
# - 4317: OTLP gRPC (OpenTelemetry export)
# - 16686: Jaeger UI (http://localhost:16686)

set -e

echo "🔭 Starting Jaeger all-in-one (Podman)..."

podman run -d \
  --name jaeger \
  -p 4317:4317 \
  -p 16686:16686 \
  jaegertracing/all-in-one:latest

echo "✅ Jaeger started!"
echo ""
echo "📊 Jaeger UI: http://localhost:16686"
echo "🔌 OTLP endpoint: localhost:4317"
echo ""
echo "To stop: podman stop jaeger"
echo "To remove: podman rm jaeger"
