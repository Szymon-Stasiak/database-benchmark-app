#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")" && pwd)"
PORTS=(8001 8080 5173)
PIDS=()

free_ports() {
    for port in "${PORTS[@]}"; do
        local pids
        pids=$(lsof -ti :"$port" 2>/dev/null || true)
        if [ -n "$pids" ]; then
            echo "Killing processes on port $port..."
            echo "$pids" | xargs kill 2>/dev/null || true
        fi
    done
    sleep 1
    for port in "${PORTS[@]}"; do
        local pids
        pids=$(lsof -ti :"$port" 2>/dev/null || true)
        if [ -n "$pids" ]; then
            echo "Force-killing stubborn processes on port $port..."
            echo "$pids" | xargs kill -9 2>/dev/null || true
        fi
    done
    sleep 1
}

free_ports

cleanup() {
    echo ""
    echo "Stopping all services..."
    for pid in "${PIDS[@]}"; do
        kill "$pid" 2>/dev/null && wait "$pid" 2>/dev/null || true
    done
    echo "All services stopped."
}
trap cleanup EXIT INT TERM

# --- Script Creator (FastAPI, port 8001) ---
echo "Starting script-creator on :8001..."
(
    cd "$REPO_ROOT/services/script-creator"
    PYTHONPATH=src python -m uvicorn dbagnets.api:app --port 8001 --reload
) &
PIDS+=($!)

# --- Backend (Spring Boot, port 8080) ---
echo "Starting backend on :8080..."
(
    cd "$REPO_ROOT/services/backend"
    ./mvnw -q spring-boot:run
) &
PIDS+=($!)

# --- Frontend (Vite, port 5173) ---
echo "Starting frontend on :5173..."
(
    cd "$REPO_ROOT/services/frontend"
    npm run dev -- --port 5173 --strictPort
) &
PIDS+=($!)

echo ""
echo "All services starting:"
echo "  frontend        → http://localhost:5173"
echo "  backend         → http://localhost:8080"
echo "  script-creator  → http://localhost:8001"
echo ""
echo "Press Ctrl+C to stop all."

wait