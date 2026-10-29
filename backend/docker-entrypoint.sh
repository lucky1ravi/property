#!/bin/sh
# Starts one service (SERVICE=name) or, when unset, the whole platform with the gateway on $PORT.
set -u
PUBLIC_PORT="${PORT:-8080}"
JAVA_OPTS="${JAVA_OPTS:--XX:+UseSerialGC -Xss512k}"

if [ -n "${SERVICE:-}" ]; then
  exec env PORT="$PUBLIC_PORT" java $JAVA_OPTS -jar "/app/${SERVICE}.jar"
fi

# All-in-one: internal services on fixed local ports, gateway on the public port.
export IDENTITY_URL=http://localhost:8081 LEASING_URL=http://localhost:8082 BILLING_URL=http://localhost:8083
export MAINTENANCE_URL=http://localhost:8084 WORKFLOW_URL=http://localhost:8085 AI_URL=http://localhost:8086 DOCUMENTS_URL=http://localhost:8087
HEAP="${SERVICE_HEAP:--Xmx200m}"

start() { # name port
  PORT="$2" java $JAVA_OPTS $HEAP -XX:MaxRAMPercentage=0 -jar "/app/$1.jar" > "/tmp/$1.log" 2>&1 &
  echo "started $1 on $2"
}
wait_up() { # port
  i=0; while [ $i -lt 120 ]; do
    if curl -fs -o /dev/null "http://localhost:$1/actuator/health"; then return 0; fi
    i=$((i+1)); sleep 2
  done; echo "service on $1 did not become healthy"; return 1
}

# Identity first: it creates the shared platform schema and roles the others depend on.
start identity-service 8081; wait_up 8081 || { tail -50 /tmp/identity-service.log; exit 1; }
start leasing-service 8082; start billing-service 8083; start maintenance-service 8084
start workflow-service 8085; start ai-service 8086; start document-service 8087
for p in 8082 8083 8084 8085 8086 8087; do wait_up $p || exit 1; done
PORT="$PUBLIC_PORT" exec java $JAVA_OPTS $HEAP -XX:MaxRAMPercentage=0 -jar /app/api-gateway.jar
