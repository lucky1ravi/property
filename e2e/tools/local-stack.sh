#!/usr/bin/env bash
# Starts the whole PropertyOS stack on this machine for the e2e suite:
#   PostgreSQL (must already be running) -> 8 Spring Boot services -> static web app + /api proxy on :4173
# Usage: tools/local-stack.sh start | stop | status
# Needs: JDK 21, the built jars (cd backend && mvn -DskipTests package), the built frontend (cd frontend && npm run build).
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
RUN="$ROOT/e2e/.run"; mkdir -p "$RUN"
export PGHOST="${PGHOST:-localhost}" PGPORT="${PGPORT:-5432}" PGUSER="${PGUSER:-postgres}" PGPASSWORD="${PGPASSWORD:-postgres}" PGDATABASE="${PGDATABASE:-e2e}"
export DEMO_MODE=true DB_POOL_SIZE=5 TZ=UTC
export RATE_LIMIT_USER_PER_MINUTE=1000000 RATE_LIMIT_PUBLIC_PER_MINUTE=1000000
export IDENTITY_URL=http://localhost:8081 LEASING_URL=http://localhost:8082 BILLING_URL=http://localhost:8083 MAINTENANCE_URL=http://localhost:8084
export WORKFLOW_URL=http://localhost:8085 AI_URL=http://localhost:8086 DOCUMENTS_URL=http://localhost:8087
export ALLOWED_ORIGINS="http://localhost:4173"
SERVICES="identity-service:8081 leasing-service:8082 billing-service:8083 maintenance-service:8084 workflow-service:8085 ai-service:8086 document-service:8087 api-gateway:8080"

wait_up() { for _ in $(seq 1 120); do curl -fs -o /dev/null "http://localhost:$1/actuator/health" && return 0; sleep 2; done; echo "port $1 did not come up"; return 1; }

start() {
  [ -f "$ROOT/frontend/dist/index.html" ] || { echo "Build the frontend first: cd frontend && npm run build"; exit 1; }
  for s in $SERVICES; do
    name="${s%%:*}"; port="${s##*:}"
    jar="$ROOT/backend/$name/target/app.jar"; [ -f "$jar" ] || { echo "Missing $jar (run mvn -DskipTests package in backend)"; exit 1; }
    PORT="$port" nohup java -Xmx300m -XX:+UseSerialGC -jar "$jar" > "$RUN/$name.log" 2>&1 &
    echo $! > "$RUN/$name.pid"
    [ "$name" = identity-service ] && wait_up "$port"   # identity creates the shared schema first
  done
  for s in $SERVICES; do wait_up "${s##*:}" || exit 1; done
  nohup node "$ROOT/e2e/tools/serve.mjs" > "$RUN/web.log" 2>&1 & echo $! > "$RUN/web.pid"
  sleep 1; echo "Stack is up: web http://localhost:4173  api http://localhost:8080"
}
stop() { for f in "$RUN"/*.pid; do [ -f "$f" ] && kill "$(cat "$f")" 2>/dev/null; rm -f "$f"; done; echo stopped; }
status() { for s in $SERVICES; do curl -fs -o /dev/null "http://localhost:${s##*:}/actuator/health" && echo "up   $s" || echo "DOWN $s"; done; curl -fs -o /dev/null http://localhost:4173/ && echo "up   web" || echo "DOWN web"; }
case "${1:-}" in start) start;; stop) stop;; status) status;; *) echo "usage: $0 start|stop|status";; esac
