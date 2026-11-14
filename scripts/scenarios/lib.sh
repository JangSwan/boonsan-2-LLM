#!/usr/bin/env bash
# 시나리오 공통 도구. MODE=compose(기본) 또는 MODE=local (scripts/local-run.sh로 띄운 경우).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MODE="${MODE:-compose}"
OUT="${OUT:-$ROOT/.run/scenarios}"
mkdir -p "$OUT"

FG=http://localhost:8080
BROKER_PRIMARY=http://localhost:8081
BROKER_REPLICA=http://localhost:8082
MM=http://localhost:8110
MON=http://localhost:8120
AWS=http://localhost:8130

log() { printf '\033[1m[%s] %s\033[0m\n' "$(date +%H:%M:%S)" "$*"; }
fail() { printf '\033[31m실패: %s\033[0m\n' "$*" >&2; exit 1; }
pass() { printf '\033[32m통과: %s\033[0m\n' "$*"; }

svc_stop() {
  if [ "$MODE" = local ]; then "$ROOT/scripts/local-run.sh" stop "$1"; else docker compose stop "$1" >/dev/null; fi
}

svc_logs() {
  if [ "$MODE" = local ]; then cat "$ROOT/.run/logs/$1.log"; else docker compose logs --no-color "$1"; fi
}

# 서비스 로그에 패턴이 있는가. grep -q는 pipefail에서 앞 명령을 SIGPIPE로 실패시키므로 끝까지 읽는다.
has_log() {
  local count
  count=$(svc_logs "$1" 2>/dev/null | grep -cE "$2" || true)
  [ "${count:-0}" -gt 0 ]
}

# 로그에 이벤트가 나타날 때까지 기다린다: wait_log <서비스> <패턴> <초>
wait_log() {
  local svc=$1 pattern=$2 timeout=$3
  for _ in $(seq 1 "$timeout"); do
    has_log "$svc" "$pattern" && return 0
    sleep 1
  done
  fail "$svc 로그에 '$pattern'이(가) ${timeout}초 안에 나타나지 않음"
}

# 질문을 보내고 SSE 응답 전체를 파일에 저장한다: ask <이름> <본문> [최대초]
ask() {
  local name=$1 text=$2 max=${3:-60}
  printf '%s' "$text" >"$OUT/$name.txt"
  jq -n --rawfile t "$OUT/$name.txt" '{text: $t}' | curl -sN -X POST "$FG/api/front-gateway/questions" \
    -H 'Content-Type: application/json' -H 'Accept: text/event-stream' --data-binary @- \
    --max-time "$max" >"$OUT/$name.sse" 2>/dev/null || true
}

# 백그라운드로 질문하고, 첫 토큰을 받은 시각(밀리초)을 따로 기록한다.
ask_timed() {
  local name=$1 text=$2 max=${3:-60}
  printf '%s' "$text" >"$OUT/$name.txt"
  (
    start=$(date +%s%3N)
    jq -n --rawfile t "$OUT/$name.txt" '{text: $t}' | curl -sN -X POST "$FG/api/front-gateway/questions" \
      -H 'Content-Type: application/json' -H 'Accept: text/event-stream' --data-binary @- --max-time "$max" 2>/dev/null \
      | while IFS= read -r line; do
          if [[ "$line" == *'"isFirst":true'* && ! -f "$OUT/$name.first" ]]; then
            echo $(( $(date +%s%3N) - start )) >"$OUT/$name.first"
          fi
          echo "$line"
        done >"$OUT/$name.sse" || true
  ) &
}

completed() { grep -q '"isLast":true' "$OUT/$1.sse"; }
failed_event() { grep -q '^event:failure' "$OUT/$1.sse"; }
tokens_of() { grep -c '^event:token' "$OUT/$1.sse" || true; }

assert_completed() {
  completed "$1" || fail "$1 답변이 끝까지 오지 않음 ($(tokens_of "$1")개 토큰)"
}

# 같은 순번이 두 번 오지 않았는지 (DuplicateFilter)
assert_no_duplicate_seq() {
  local dup
  dup=$(grep '^data:.*"seq"' "$OUT/$1.sse" | sed 's/^data://' | jq -r '.seq' | sort -n | uniq -d | head -1)
  [ -z "$dup" ] || fail "$1에서 순번 $dup 중복"
}

long_text() { python3 -c "print('긴 문서 요약을 부탁합니다. ' * $1)"; }
