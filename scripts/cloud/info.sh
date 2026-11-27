#!/usr/bin/env bash
# 접속 주소를 보여 준다. 공인 IP는 켤 때마다 바뀌므로 켤 때마다 확인한다.
#   scripts/cloud/info.sh
source "$(dirname "$0")/lib.sh"
public=$(imds public-ipv4)
private=$(imds local-ipv4)
echo "모드: $(env_get LLM_MODE) / 크기: $(env_get SERVER_SIZE) / 유형별 인출: $(env_get BROKER_TYPED_PULL)"
if [ -n "$public" ]; then
  echo "이용자 화면:  http://$public:8140"
  echo "답변 월:      http://$public:8140/wall.html"
  echo "운영자 화면:  http://$public:8140/admin.html"
else
  echo "공인 IP를 알 수 없습니다 (EC2가 아니거나 공인 IP가 없음)"
fi
if [ -n "$private" ]; then
  echo "GPU 서버 .env의 CONTROL_IP: $private (프라이빗 IP, 중지·시작해도 그대로)"
fi
