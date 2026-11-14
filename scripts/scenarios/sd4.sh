#!/usr/bin/env bash
# sd4. 요청 유발 장애 격리 (QAS 1, 3): 엔진을 죽이는 요청은 재시도 상한(2회) 후 격리되고, 그 1건만 실패한다.
source "$(dirname "$0")/lib.sh"

log "sd4: 엔진을 죽이는 질문 1건 + 정상 질문"
ask_timed "sd4-X" "[[crash]] 엔진을 비정상 종료시키는 질문" 90
sleep 0.3
for i in 1 2 3 4 5 6; do
  ask "sd4-ok-$i" "정상 질문 $i" 90 &
done
wait

failed_event "sd4-X" || fail "격리된 요청이 실패 응답을 받지 못함"
grep -q 'REQUEST_QUARANTINED' "$OUT/sd4-X.sse" || fail "실패 코드가 REQUEST_QUARANTINED가 아님"
for i in 1 2 3 4 5 6; do assert_completed "sd4-ok-$i"; done

has_log broker-primary 'event=retry.requeued.*retryCount=1' || fail "1회차 만료 후 재투입 로그 없음"
has_log broker-primary 'event=retry.quarantined.*retryCount=2' || fail "2회차 만료 후 격리 로그 없음"


quarantined=$(curl -s "$BROKER_PRIMARY/api/broker/queue/metrics" | jq -r '.quarantinedCount')
[ "$quarantined" -ge 1 ] || fail "격리 큐가 비어 있음"
pass "sd4: 만료 1회차 재투입 → 2회차 격리 → 실패 통지, 정상 질문 6건 완료"
