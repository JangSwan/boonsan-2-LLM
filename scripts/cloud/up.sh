#!/usr/bin/env bash
# 앱을 띄운다 (이미 떠 있으면 바뀐 설정의 서비스만 다시 만든다). 서버가 켜질 때 systemd(llm-control)가 이 스크립트를 부른다.
#   scripts/cloud/up.sh
source "$(dirname "$0")/lib.sh"
require_env
compose up -d
