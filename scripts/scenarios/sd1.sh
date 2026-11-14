#!/usr/bin/env bash
# sd1. 순번 안내와 크레딧 Pull (QAS 1): 처리 한도를 넘는 동시 질문도 거절 없이 순번으로 안내하고 모두 답한다.
source "$(dirname "$0")/lib.sh"

N=${N:-60}
log "sd1: 동시 질문 $N건 (동시 실행 상한 32 초과)"
for i in $(seq 1 "$N"); do
  ask "sd1-$i" "동시 질문 $i 입니다. 짧게 답해 주세요." 90 &
done

max_active=0
for _ in $(seq 1 20); do
  active=$(curl -s "$BROKER_PRIMARY/api/broker/queue/metrics" | jq -r '.activeLeases // 0')
  [ "$active" -gt "$max_active" ] && max_active=$active
  sleep 0.5
done
wait

done_count=0; positions=0
for i in $(seq 1 "$N"); do
  completed "sd1-$i" && done_count=$((done_count + 1))
  grep -q '^event:position' "$OUT/sd1-$i.sse" && positions=$((positions + 1))
  failed_event "sd1-$i" && fail "sd1-$i 실패 응답"
  assert_no_duplicate_seq "sd1-$i"
done

[ "$done_count" -eq "$N" ] || fail "완료 $done_count/$N"
[ "$positions" -eq "$N" ] || fail "순번 안내 $positions/$N"
[ "$max_active" -le 32 ] || fail "동시 실행 $max_active > 4대분 상한 32"
has_log broker-primary 'event=request.enqueued' || fail "request.enqueued 로그 없음"
has_log broker-primary 'event=lease.completed' || fail "lease.completed 로그 없음"
pass "sd1: $N건 모두 순번 안내 후 완료, 관측된 최대 동시 실행 $max_active (≤ 32), 중복 순번 없음"
