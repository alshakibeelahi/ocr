#!/bin/sh
set -eu

MODEL="${OLLAMA_MODEL:-qwen2.5vl:3b}"
HOST="${OLLAMA_HOST:-http://ollama:11434}"

echo "Waiting for Ollama at ${HOST}..."
until OLLAMA_HOST="${HOST}" ollama list > /dev/null 2>&1; do
  sleep 2
done

echo "Pulling model: ${MODEL}"
OLLAMA_HOST="${HOST}" ollama pull "${MODEL}"

echo "Model pull complete."
OLLAMA_HOST="${HOST}" ollama list
