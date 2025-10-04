#!/usr/bin/env bash
# Ollama management script for Podman

set -e

OLLAMA_POD_YAML="ollama-pod.yaml"
OLLAMA_DATA_DIR="${HOME}/.ollama"

case "${1:-}" in
  start)
    echo "🚀 Starting Ollama pod..."
    mkdir -p "$OLLAMA_DATA_DIR"
    podman play kube "$OLLAMA_POD_YAML"
    echo "✅ Ollama is starting up..."
    echo "📍 API available at: http://localhost:11434"
    ;;
  
  stop)
    echo "🛑 Stopping Ollama pod..."
    podman play kube --down "$OLLAMA_POD_YAML"
    echo "✅ Ollama stopped"
    ;;
  
  restart)
    echo "🔄 Restarting Ollama pod..."
    podman play kube --down "$OLLAMA_POD_YAML" 2>/dev/null || true
    sleep 2
    podman play kube "$OLLAMA_POD_YAML"
    echo "✅ Ollama restarted"
    ;;
  
  status)
    echo "📊 Ollama pod status:"
    podman pod ps --filter name=ollama
    echo ""
    echo "📊 Container status:"
    podman ps --filter label=app=ollama
    ;;
  
  logs)
    echo "📜 Ollama logs:"
    podman logs -f ollama-ollama
    ;;
  
  pull)
    MODEL="${2:-qwen2.5:0.5b}"
    echo "📥 Pulling model: $MODEL"
    podman exec -it ollama-ollama ollama pull "$MODEL"
    echo "✅ Model pulled: $MODEL"
    ;;
  
  list)
    echo "📋 Installed models:"
    podman exec -it ollama-ollama ollama list
    ;;
  
  run)
    MODEL="${2:-qwen2.5:0.5b}"
    echo "🤖 Running interactive chat with: $MODEL"
    podman exec -it ollama-ollama ollama run "$MODEL"
    ;;
  
  test)
    echo "🧪 Testing Ollama API..."
    curl -s http://localhost:11434/api/tags | jq '.' || echo "❌ Ollama not responding"
    ;;
  
  chat-test)
    MODEL="${2:-qwen2.5:0.5b}"
    echo "🧪 Testing chat API with: $MODEL"
    curl -s http://localhost:11434/v1/chat/completions \
      -H "Content-Type: application/json" \
      -d "{
        \"model\": \"$MODEL\",
        \"messages\": [
          {\"role\": \"user\", \"content\": \"Say hello in one sentence.\"}
        ]
      }" | jq '.'
    ;;
  
  *)
    cat <<EOF
📦 Ollama Podman Manager

Usage: $0 <command> [args]

Commands:
  start           Start Ollama pod
  stop            Stop Ollama pod
  restart         Restart Ollama pod
  status          Show pod and container status
  logs            Follow Ollama logs
  pull [model]    Pull a model (default: qwen2.5:0.5b)
  list            List installed models
  run [model]     Run interactive chat (default: qwen2.5:0.5b)
  test            Test if Ollama API is responding
  chat-test [model]  Test OpenAI-compatible chat endpoint

Examples:
  $0 start                    # Start Ollama
  $0 pull qwen2.5:0.5b       # Pull qwen 0.5b model
  $0 run qwen2.5:0.5b        # Chat with qwen
  $0 chat-test qwen2.5:0.5b  # Test API endpoint
  $0 status                   # Check status
  $0 stop                     # Stop Ollama

After starting:
  1. Run: $0 pull qwen2.5:0.5b
  2. Test: $0 chat-test qwen2.5:0.5b
  3. Start coding!

EOF
    exit 1
    ;;
esac
