#!/bin/bash
# Start the full OpenTelemetry observability stack using Podman Compose
# Includes: OTel Collector, Jaeger, Prometheus, Grafana

set -e

echo "🚀 Starting OpenTelemetry Observability Stack..."
echo ""

# Check if podman-compose is installed
if ! command -v podman compose &> /dev/null; then
    echo "❌ podman-compose not found!"
    echo "Install with: pip3 install podman-compose"
    echo "Or use: brew install podman-compose (macOS)"
    exit 1
fi

# Start the stack
podman compose -f podman-compose.yaml up -d

echo ""
echo "✅ Observability stack started!"
echo ""
echo "📊 Access your observability tools:"
echo "  • Jaeger UI:       http://localhost:16686  (traces)"
echo "  • Prometheus UI:   http://localhost:9090   (metrics)"
echo "  • Grafana UI:      http://localhost:3000   (dashboards, login: admin/admin)"
echo "  • OTel Collector:  localhost:4317          (OTLP gRPC endpoint)"
echo ""
echo "🔍 Your app should point to: localhost:4317 (OTLP gRPC)"
echo ""
echo "To view logs:   podman-compose -f podman-compose.yaml logs -f"
echo "To stop:        podman-compose -f podman-compose.yaml down"
echo "To restart:     podman-compose -f podman-compose.yaml restart"
