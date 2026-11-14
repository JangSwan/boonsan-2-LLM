#!/usr/bin/env bash
# sd3. 스팟 회수 감지와 재분배 (QAS 3): 회수 통지 → 인출 중지 → 서버 소멸 → 제외·회수·재분배 → 재보충·합류.
source "$(dirname "$0")/lib.sh"

alerts() { curl -s "$MON/api/monitoring/alerts"; }

log "sd3: 진행 중 질문을 깔아 두고 worker-5 스팟 회수 통지"
for i in $(seq 1 20); do ask "sd3-before-$i" "회수 전 질문 $i" 120 & done
sleep 1
curl -sf -X POST "$AWS/api/fake-aws/spot-reclaims" -H 'Content-Type: application/json' \
  -d '{"workerId":"worker-5"}' >/dev/null || fail "회수 통지 시연 명령 실패"
wait_log worker-5 'event=worker.pull-stopped' 10
wait_log membership-1 'event=worker.reclaim-scheduled workerId=worker-5' 10

log "sd3: 서버 소멸 (worker-5 정지)"
svc_stop worker-5
gone_at=$(date +%s)
for i in $(seq 1 20); do ask "sd3-after-$i" "회수 후 질문 $i" 120 & done

leader=""
for _ in $(seq 1 30); do
  for m in membership-1 membership-2; do
    svc_logs $m 2>/dev/null | grep -q 'event=worker.excluded workerId=worker-5' && leader=$m
  done
  [ -n "$leader" ] && break
  sleep 1
done
[ -n "$leader" ] || fail "worker-5가 제외되지 않음"
other=$([ "$leader" = membership-1 ] && echo membership-2 || echo membership-1)
svc_logs "$other" | grep -q 'event=worker.excluded workerId=worker-5' && fail "리더가 아닌 $other도 제외를 실행함"
wait_log "$leader" 'event=recovery.service workerId=worker-5' 20
t_service=$(( $(date +%s) - gone_at ))
alerts | jq -e 'any(.[]; .kind == "recovery.service")' >/dev/null || fail "복구 알림(T서비스)이 Monitoring에 없음"

log "sd3: 재보충 (worker-spare 기동 → 적재·시험 → 합류)"
wait_log fake-aws 'event=aws.instance-ready instanceId=worker-spare' 30
wait_log worker-spare 'event=worker.ready' 30
wait_log "$leader" 'event=recovery.replenished workerId=worker-5' 30
t_replenish=$(( $(date +%s) - gone_at ))
alerts | jq -e 'any(.[]; .kind == "recovery.replenished")' >/dev/null || fail "합류 알림(T재보충)이 Monitoring에 없음"
wait

for i in $(seq 1 20); do
  assert_completed "sd3-before-$i"; assert_no_duplicate_seq "sd3-before-$i"
  assert_completed "sd3-after-$i"
done
[ "$t_service" -le 300 ] || fail "T서비스 ${t_service}초 > 300초"
pass "sd3: 리더($leader)만 제외·회수·재보충, T서비스 약 ${t_service}초 (≤ 300초), T재보충 약 ${t_replenish}초, 질문 40건 모두 완료"
