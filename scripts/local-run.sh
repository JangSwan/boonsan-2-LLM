#!/usr/bin/env bash
# Docker 없이 jar로 전체를 띄운다 (mock 프로필). docker compose와 같은 포트를 쓴다.
#   ./gradlew build -x test && scripts/local-run.sh start
#   scripts/local-run.sh stop <서비스>   # 갑작스러운 장애 (docker compose stop과 같은 역할)
#   scripts/local-run.sh start <서비스>  # 한 서비스만 다시 띄우기
#   scripts/local-run.sh down            # 전부 정지
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUN="$ROOT/.run"
mkdir -p "$RUN/logs" "$RUN/pids"

H=http://localhost
BROKERS="$H:8081,$H:8082"
MEMBERSHIPS="$H:8101,$H:8102"
WORKERS="worker-1=$H:8091,worker-2=$H:8092,worker-3=$H:8093,worker-4=$H:8094,worker-5=$H:8095,worker-spare=$H:8096"
COMMON_ENV="SPRING_PROFILES_ACTIVE=mock REDIS_HOST=localhost FRONT_GATEWAY_URL=$H:8080 MODEL_MANAGER_URL=$H:8110"

SERVICES="broker-replica broker-primary membership-1 membership-2 model-manager monitoring fake-aws front-gateway worker-1 worker-2 worker-3 worker-4 worker-5 worker-spare client"

env_of() {
  case "$1" in
    broker-replica) echo "broker 8082 BROKER_ROLE=replica" ;;
    broker-primary) echo "broker 8081 BROKER_ROLE=primary REPLICA_URL=$H:8082" ;;
    membership-*)   echo "membership 810${1#membership-} BROKER_URLS=$BROKERS WORKER_URLS=$WORKERS MONITORING_URL=$H:8120 FAKE_AWS_URL=$H:8130" ;;
    model-manager)  echo "model-manager 8110 MODEL_CONFIG_FILE=$RUN/model-config.properties" ;;
    monitoring)     echo "monitoring 8120 BROKER_URLS=$BROKERS WORKER_URLS=$WORKERS" ;;
    fake-aws)       echo "fake-aws 8130 MEMBERSHIP_URLS=$MEMBERSHIPS SPARE_INSTANCE_URLS=worker-spare=$H:8096" ;;
    front-gateway)  echo "front-gateway 8080 BROKER_URLS=$BROKERS" ;;
    worker-spare)   echo "worker 8096 WORKER_START_ON_BOOT_SIGNAL=true BROKER_URLS=$BROKERS MEMBERSHIP_URLS=$MEMBERSHIPS" ;;
    worker-*)       echo "worker 809${1#worker-} BROKER_URLS=$BROKERS MEMBERSHIP_URLS=$MEMBERSHIPS" ;;
    client)         echo "client 8140" ;;
    *) echo "알 수 없는 서비스: $1" >&2; exit 1 ;;
  esac
}

start_one() {
  local svc=$1; read -r module port extra <<<"$(env_of "$svc")"
  if [ -f "$RUN/pids/$svc.pid" ] && kill -0 "$(cat "$RUN/pids/$svc.pid")" 2>/dev/null; then return; fi
  # shellcheck disable=SC2086
  env $COMMON_ENV INSTANCE_ID="$svc" SERVER_PORT="$port" $extra \
    nohup java -Xms64m -Xmx256m -XX:TieredStopAtLevel=1 -Djdk.httpclient.keepalive.timeout=3 -jar "$ROOT/$module/build/libs/$module.jar" \
    >"$RUN/logs/$svc.log" 2>&1 &
  echo $! >"$RUN/pids/$svc.pid"
}

wait_healthy() {
  local svc=$1; read -r _ port _ <<<"$(env_of "$svc")"
  for _ in $(seq 1 90); do
    curl -fs "$H:$port/actuator/health" >/dev/null 2>&1 && return 0
    sleep 1
  done
  echo "$svc가 준비되지 않았습니다 (로그: $RUN/logs/$svc.log)" >&2; return 1
}

stop_one() {
  local pidfile="$RUN/pids/$1.pid"
  [ -f "$pidfile" ] || return 0
  kill -9 "$(cat "$pidfile")" 2>/dev/null || true
  rm -f "$pidfile"
}

case "${1:-start}" in
  start)
    if [ $# -ge 2 ]; then start_one "$2"; wait_healthy "$2"; exit 0; fi
    redis-cli -p 6379 ping >/dev/null 2>&1 || redis-server --port 6379 --save "" --daemonize yes >/dev/null
    rm -f "$RUN/model-config.properties"
    for svc in broker-replica; do start_one $svc; done; wait_healthy broker-replica
    for svc in $SERVICES; do start_one "$svc"; done
    for svc in $SERVICES; do wait_healthy "$svc"; done
    echo "전체 기동 완료: Web UI $H:8140, 운영자 $H:8140/admin.html"
    ;;
  stop) stop_one "$2" ;;
  down) for svc in $SERVICES; do stop_one "$svc"; done ;;
  logs) cat "$RUN"/logs/*.log ;;
  *) echo "사용법: $0 start [서비스] | stop <서비스> | down | logs" >&2; exit 1 ;;
esac
