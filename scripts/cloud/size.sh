#!/usr/bin/env bash
# 서버 크기에 맞춰 앱 메모리 상한을 바꾼다. 인스턴스 유형을 바꾼 뒤(콘솔에서 중지 → 유형 변경 → 시작) 실행한다.
#   scripts/cloud/size.sh light     # t3.medium (4GB): 평소
#   scripts/cloud/size.sh measure   # m7i.2xlarge (32GB): QAS 측정 날 (폭주 1,000건)
source "$(dirname "$0")/lib.sh"
require_env
case "${1:-}" in
  light)   small=256m; medium=320m; large=448m ;;
  measure) small=512m; medium=768m; large=2g ;;
  *) echo "사용법: $0 light|measure" >&2; exit 1 ;;
esac
env_set SERVER_SIZE "$1"
env_set MEM_SMALL "$small"
env_set MEM_MEDIUM "$medium"
env_set MEM_LARGE "$large"
compose up -d
echo "크기: $1 (MEM_SMALL=$small, MEM_MEDIUM=$medium, MEM_LARGE=$large)"
