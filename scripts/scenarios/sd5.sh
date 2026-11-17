#!/usr/bin/env bash
# sd5. 브로커 복제본 승격 (QAS 1): 주 브로커가 사라져도 순번을 받은 요청은 잃지 않고, 복제본이 이어받는다.
source "$(dirname "$0")/lib.sh"

log "sd5: 대기열을 만든 뒤 주 브로커 정지"
for i in $(seq 1 40); do ask "sd5-before-$i" "승격 전 질문 $i" 120 & done
sleep 1.5
queued=$(curl -s "$BROKER_PRIMARY/api/broker/queue/metrics" | jq -r '.queueLength + .activeLeases')
code=$(curl -s -o /dev/null -w '%{http_code}' "$BROKER_REPLICA/api/broker/requests/x/position")
[ "$code" = 503 ] || fail "승격 전 복제본이 순번 조회에 $code로 응답 (503 기대)"
svc_stop broker-primary
stopped_at=$(( $(now_ms) / 1000 ))

wait_log broker-replica 'event=broker.promoted epoch=2' 30
promote_sec=$(( $(( $(now_ms) / 1000 )) - stopped_at ))
for i in $(seq 1 10); do ask "sd5-after-$i" "승격 후 질문 $i" 120 & done
wait

for i in $(seq 1 40); do assert_completed "sd5-before-$i"; assert_no_duplicate_seq "sd5-before-$i"; done
for i in $(seq 1 10); do assert_completed "sd5-after-$i"; done
metrics=$(curl -s "$BROKER_REPLICA/api/broker/queue/metrics")
[ "$(echo "$metrics" | jq -r .queueLength)" = 0 ] || fail "승격 후 큐가 비지 않음: $metrics"
pass "sd5: 승격 약 ${promote_sec}초, 승격 시점 대기·실행 ${queued}건 포함 승격 전 40건과 승격 후 10건 모두 완료 (잃은 요청 0)"
