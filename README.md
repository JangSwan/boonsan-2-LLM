# LLM Serving Platform

자체 호스팅 오픈소스 LLM(8B) 서빙 플랫폼 (소프트웨어 아키텍처 수업 팀 프로젝트). 이용자 약 1,000명 규모 설계를 코드로 옮겼다.
규칙은 [`CLAUDE.md`](CLAUDE.md), 클래스·오퍼레이션·호출 흐름은 [`docs/design.md`](docs/design.md)(EA 모델)를 따른다.

| 전략 | 내용 | 구현 위치 |
|---|---|---|
| C2 | 공용 대기열 + 실행 크레딧 Pull | `broker` (AdmissionController, SharedQueue, LeaseManager), `worker` (CreditManager, LeaseRenewer) |
| T3 | 시간 기준 분할 (스텝 예산 B, Prefill 조각) | `worker` (EngineScheduler, BudgetPolicy/BudgetController, BudgetGuard) |
| U1 | 모델 축소 사다리, 품질 게이트, 롤백 | `model-manager` (ModelConfig, QualityGate, RollbackManager), `worker` (ModelRuntime) |
| R3 | N+1 활성 여분 (5대 운영, 4대분 상한) | `membership` (HealthDetector, LeaseReclaimer, Replenisher, LeaderLock), `broker` (LeaseLimiter) |

## 모듈

| 모듈 | 포트 | 역할 |
|---|---|---|
| `common` | – | 요청/응답 DTO, API 경로 상수 (로직 없음) |
| `front-gateway` | 8080 | 질문 접수(POST → SSE), 요청 ID·버전 태그, 토큰 중계, 중복 필터 |
| `broker` | 8081 / 8082 | 접수, 공용 큐, lease, 4대분 상한, 재시도·격리, 동기 복제와 쿼럼 승격 (주 / 복제본) |
| `worker` | 8091~8095 / 8096 | 크레딧 pull, 스텝 예산, 엔진 스케줄링, 가짜 엔진, lease 갱신, 하트비트 (5대 / 예비) |
| `membership` | 8101 / 8102 | 하트비트 감지, 회수 통지, lease 회수, 재보충, 복구 알림, 리더 락 |
| `model-manager` | 8110 | 모델 교체, 사다리 단계, 품질 게이트, 버전 비율, 롤백 |
| `monitoring` | 8120 | 지표 수집, 알림, 품질 샘플 전달 |
| `fake-aws` | 8130 | 스팟 회수 통지, 대체 인스턴스 기동 흉내 (mock) |
| `client` | 8140 | Web UI(`/`), AdminConsole(`/admin.html`), 외부 프로그램용 `ApiClient` |
| redis | 6379 | Membership 리더 락, `broker-primary` 락 (두 락에만 사용) |

## 실행

```bash
./gradlew build            # 빌드 + 단위·구조(ArchUnit) 테스트
docker compose up --build  # 전체 실행 (mock 프로필)
```

- 이용자 화면: http://localhost:8140 , 운영자 화면: http://localhost:8140/admin.html
- 명령줄 질문: `curl -N -X POST localhost:8080/api/front-gateway/questions -H 'Content-Type: application/json' -d '{"text":"안녕하세요"}'`
- Docker 없이 실행: `./gradlew build -x test && scripts/local-run.sh start` (같은 포트, `scripts/local-run.sh stop worker-5`로 장애 시연)

## 시나리오 (sd1 ~ sd6)

전체를 띄운 뒤 `scripts/scenarios/run-all.sh`(Docker 없이 띄웠으면 `MODE=local`)로 자동 확인한다. CI의 `scenarios` job이 docker compose 위에서 같은 스크립트를 돌린다.

| 시나리오 | 스크립트가 하는 일 | 확인하는 것 |
|---|---|---|
| sd1 순번 안내·크레딧 pull | 동시 질문 60건 | 모두 순번 안내 후 완료, 동시 실행 ≤ 32(4대분), 중복 순번 없음 |
| sd2 스텝 예산 | 긴 입력(약 4,000토큰) 5건 직후 짧은 질문 5건 | 짧은 질문이 긴 입력보다 먼저, 3초 안에 첫 토큰 |
| sd4 요청 유발 장애 격리 | `[[crash]]`가 든 질문(가짜 엔진 비정상 종료) + 정상 질문 | 만료 1회차 재투입 → 2회차 격리 → 그 1건만 실패, 정상 질문 완료 |
| sd6 모델 교체·롤백 | SMALL 교체(거부) → FP8 교체 → 품질 이상 주입 | 게이트 미통과 거부, 한 대씩 교체 후 확정, 교체·롤백 중 끊긴 답변 0건, U0 복귀 |
| sd3 스팟 회수 | fake-aws 회수 통지 → `worker-5` 정지 | 인출 중지, 리더만 제외·회수·재보충, T서비스 ≤ 300초, worker-spare 합류, 알림 |
| sd5 브로커 승격 | 대기열이 있는 상태로 `broker-primary` 정지 | 복제본 승격(epoch 2), 승격 전후 요청 모두 완료(잃은 요청 0) |

손으로 시연할 때:

```bash
curl -X POST localhost:8130/api/fake-aws/spot-reclaims -H 'Content-Type: application/json' -d '{"workerId":"worker-5"}'
docker compose stop worker-5          # 서버 소멸
docker compose stop broker-primary    # 주 브로커 장애
docker compose logs -f | grep -E 'event=(worker\.excluded|lease\.reclaimed|broker\.promoted|retry\.quarantined|model\.)'
```

로그 형식은 `[instanceId] [requestId] event=<이벤트명> key=value ... msg=<설명>`이라 요청 ID 하나로 앱 사이 흐름을 grep할 수 있다.

## real 모드

외부 의존 세 가지는 Port 하나에 mock·real 구현을 두고 Spring 프로필로만 고른다 (`SPRING_PROFILES_ACTIVE=real`).

| Port | mock | real |
|---|---|---|
| `InferenceEnginePort` (worker) | `MockInferenceEngine` | `VllmEngineAdapter` (OpenAI 호환 `/v1/completions` 스트림, `VLLM_URL`) |
| `SpotInstancePort` (membership) | `MockSpotInstanceClient` → fake-aws | `SpotInstanceClient` (AWS SDK EC2, `AWS_LAUNCH_TEMPLATE_ID`) |
| `ModelStorePort` (model-manager) | `MockModelStoreDAO` | `ModelStoreDAO` (S3) |

자격 증명과 엔진 주소는 `.env`로만 받는다 (커밋하지 않음).

## 설계에서 정하지 않아 구현에서 정한 것

`docs/design.md` 5절에 더해 구현 중 정한 것. 팀 검토가 필요하면 여기를 본다.

- **복제 묶음(BATCH)**: 인출(큐 제거 + lease 발급), 만료(lease 제거 + 재투입·격리), 회수(lease 제거 + 재투입)는 복제 메시지 하나로 커밋한다. 둘로 나누면 중간에 주 브로커가 죽을 때 요청이 사라진다 (sd5에서 실제로 재현해 고침).
- **재시도 작업 분산**: `SharedQueue.pullMatching`은 재시도 중인 작업을 pull 한 번에 1건까지만 내준다. 엔진을 죽인 요청과 같이 만료된 정상 요청이 다음 워커에 같이 실려 함께 격리되는 것을 막는다 (sd4 "그 요청 1건만 실패").
- **엔진 재시작 중 여유 0**: 엔진이 비정상 종료하면 생성 시험이 다시 통과할 때까지 `EngineScheduler.freeCapacity()`가 여유 0을 알린다.
- **승격 조건**: 복제본은 주 브로커의 메시지를 한 번이라도 받은 뒤에만 "연락 끊김"으로 승격을 검토한다 (동시 기동 때 잘못 승격하지 않게). 주 브로커는 변경이 없을 때도 생존 알림(PING)을 보낸다.
- **승격 직후 lease**: 승격하는 동안 갱신하지 못한 lease는 만료 시각을 TTL만큼 다시 준다 (기존 lease 유지).
- **교체 롤링**: `ModelConfig.onDrainStart`가 한 번에 한 대만 허가하고, 현재 버전을 인출하는 마지막 워커에는 `lastOfVersion`을 알려 큐에 남은 옛 버전 작업을 마저 가져간 뒤 멈추게 한다. 버전 태그는 `v1, v2, …`로 교체마다 새로 붙인다.
- **롤백**: 교체 도중이면 목표를 취소하고 활성 버전으로 되돌리며, 교체 완료 후면 U0를 새 버전으로 같은 롤링 절차를 쓴다.
- **품질 샘플 입력**: mock 모드에는 실제 응답 평가기가 없으므로 `POST /api/monitoring/quality-samples`로 샘플을 넣는다 (운영자 화면의 "품질 이상 주입").
- **접수 재시도**: Front Gateway는 broker가 느리거나 승격 중이면 오류 대신 같은 요청 ID로 최대 20초 동안 다시 보낸다 (broker가 같은 요청 ID를 한 번만 적재).
