# docs/design.md — 설계 상세 (EA 모델 기준)

이 문서는 EA 모델(런타임 뷰, Module View, 시퀀스 sd1~sd6)을 코드 작성용으로 옮긴 것이다. 규칙은 `CLAUDE.md`, 무엇을 만드는지는 이 문서를 따른다.
EA가 바뀌면 이 문서를 다시 만든다. 이 문서와 EA가 다르면 EA가 맞다.

- 기준 모델: 팀 EA 파일 3차 수정본 (2026-11-14)
- 3차 수정에서 EA에 반영한 것
  - Membership 차일드에 `AWS (스팟) → Replenisher : 기동 완료` 화살표 추가, Structural의 `AWS (스팟) → Membership` 라벨을 `회수 통지 · 기동 완료`로 변경
  - 실제 엔진 연결과 완료 처리를 위한 속성·오퍼레이션: `Request.prompt`, `RequestState.prompt`, `StepPlan.prompts`, `StepResult.finishedIds`, `StreamChunk.isLast`, `EngineEventListener.onCompleted`, `LeaseRenewer.onCompleted`, `CreditManager.onCompleted(requestId)`
  - `Lease.item` (lease가 작업을 담아 워커 전달, 만료 시 재시도, 회수 시 재투입에 사용)
  - `ChangeRecord.epoch` (sd5 fencing)
  - Membership 노트: 기동 완료는 두 제어기가 모두 받고 리더만 처리
- 4차 수정(2026-11-18)에서 EA에 반영한 것
  - sd1·sd2·sd4·sd6 반복 프래그먼트를 loop로, 조건 하나짜리 프래그먼트를 opt로 바로잡음
  - sd6 마지막 워커 드레인 판단용 오퍼레이션: `SharedQueue.countMatching(versionTag)`, `LeaseManager.remainingFor(versionTag)`, `CreditManager.remainingForVersion()` (5절)

## 목차
1. 런타임 뷰 (컴포넌트와 연결)
2. 패키지와 클래스
3. 클래스 관계
4. 시퀀스와 오퍼레이션 대응표 (sd1~sd6)
5. EA에 없어서 코드에서 보충하는 것

## 1. 런타임 뷰 (컴포넌트와 연결)

화살표는 "왼쪽이 오른쪽을 호출하거나 오른쪽으로 보낸다"는 뜻이다. 이 표에 없는 컴포넌트 간 호출은 만들지 않는다.

### 1.1 Structural (전체)

| 요소 | 스테레오타입(전략) |
|---|---|
| API Client | client |
| AWS (스팟) | - |
| Front Gateway | stateless |
| Membership | R3 |
| Model Manager | U1 |
| Monitoring | monitor |
| Queue Broker | C2 |
| Web UI | client |
| Worker x (4 + 1) | C2,T3,U1,R3 |
| 운영자 | - |
| 이용자 | - |

> 성능, 신뢰성 전략 배치 (색 = 전략) / - C2 공용 대기열 + 크레딧 Pull (초록): Queue Broker / - T3 시간 기준 분할 (보라): Worker 내부 스텝 예산, Prefill 조각 실행 / - U1 모델 축소 (파랑): Model Manager 단계 관리, 품질 게이트, 롤백 / - R3 N+1 활성 여분 (주황): Membership 감지, 재분배, 재보충 / - Worker x (4 + 1)은 5대 모두 같은 구성 (C2 인출, T3 실행, U1 추론, R3 여분) / - Worker와 Membership의 양방향 선은 의도한 제어 루프 (하트비트 보고, 인출 중지 지시) / - oo 표시: 차일드 다이어그램에 세부 구조

| 보내는 쪽 | 받는 쪽 | 내용 |
|---|---|---|
| API Client | Front Gateway |  |
| AWS (스팟) | Membership | 회수 통지 · 기동 완료 ※라벨 변경 |
| Front Gateway | Model Manager | 버전 비율 조회 |
| Front Gateway | Queue Broker | enqueue · 순번 조회 |
| Membership | AWS (스팟) | 재생성 |
| Membership | Monitoring | 장애 · 복구 알림 |
| Membership | Queue Broker | lease 회수 |
| Membership | Worker x (4 + 1) | 인출 중지 |
| Monitoring | Model Manager | 응답 샘플 전달 |
| Monitoring | Queue Broker | 큐 길이 수집 |
| Monitoring | Worker x (4 + 1) | 세션 · GPU · TTFT 수집 |
| Queue Broker | Front Gateway | 실패 통지 |
| Web UI | Front Gateway |  |
| Web UI | Model Manager | 교체 요청 |
| Web UI | Monitoring |  |
| Worker x (4 + 1) | Front Gateway | stream |
| Worker x (4 + 1) | Membership | 하트비트 · 상태 보고 |
| Worker x (4 + 1) | Model Manager | 설정 감지 · 가중치 적재 |
| Worker x (4 + 1) | Queue Broker | pull |
| 운영자 | Web UI |  |
| 이용자 | API Client |  |
| 이용자 | Web UI |  |

### 1.2 Queue Broker 차일드

| 요소 | 스테레오타입(전략) |
|---|---|
| Admission Controller | C2 |
| Broker Replica | C2 보완 |
| Lease Limiter | R3 |
| Lease Manager | C2 |
| Quarantine Queue | R3 보완 |
| Replicator | C2 보완 |
| Retry Counter | R3 보완 |
| Shared Queue | C2 |
| Front Gateway (외부) | stateless |
| Membership (외부) | R3 |
| Monitoring (외부) | monitor |
| Worker x (4 + 1) (외부) | C2 |

| 보내는 쪽 | 받는 쪽 | 내용 |
|---|---|---|
| Admission Controller | Shared Queue | 버전 태그로 적재 |
| Front Gateway | Admission Controller | enqueue · 순번 조회 |
| Lease Manager | Lease Limiter | 4대분 상한 확인 |
| Lease Manager | Replicator | lease 상태 |
| Lease Manager | Retry Counter | 만료 · 실패 보고 |
| Lease Manager | Shared Queue | 버전 일치 인출 |
| Membership | Lease Manager | lease 회수 |
| Membership | Monitoring | 장애 · 복구 알림 |
| Membership | Worker x (4 + 1) | 인출 중지 |
| Monitoring | Shared Queue | 큐 길이 수집 |
| Monitoring | Worker x (4 + 1) | 세션 · GPU · TTFT 수집 |
| Replicator | Broker Replica | 동기 복제 |
| Retry Counter | Front Gateway | 실패 통지 |
| Retry Counter | Quarantine Queue | 상한 초과 |
| Retry Counter | Shared Queue | 재투입 |
| Shared Queue | Replicator | 큐 상태 |
| Worker x (4 + 1) | Front Gateway | stream |
| Worker x (4 + 1) | Lease Manager | lease 갱신 |
| Worker x (4 + 1) | Lease Manager | pull |
| Worker x (4 + 1) | Membership | 하트비트 · 상태 보고 |

### 1.3 Worker x (4+1) 차일드

| 요소 | 스테레오타입(전략) |
|---|---|
| Budget Controller | T3 |
| Budget Guard | T3 |
| Credit Manager | C2,T3 |
| Engine Scheduler | T3 |
| Lease Renewer | C2 보완 |
| Model Runtime | U1 |
| Worker Agent | R3 |
| Front Gateway (외부) | stateless |
| Membership (외부) | R3 |
| Model Manager (외부) | U1 |
| Monitoring (외부) | monitor |
| Queue Broker (외부) | C2 |

| 보내는 쪽 | 받는 쪽 | 내용 |
|---|---|---|
| Budget Controller | Budget Guard | B후보 |
| Budget Guard | Engine Scheduler | 스텝 예산 B |
| Credit Manager | Engine Scheduler | 여유 조회 · 작업 투입 |
| Credit Manager | Queue Broker | pull |
| Engine Scheduler | Budget Controller | 백로그 |
| Engine Scheduler | Credit Manager | 첫 토큰 · 미시작 토큰 |
| Engine Scheduler | Lease Renewer | 진척 보고 |
| Engine Scheduler | Model Runtime | 스텝 실행 |
| Front Gateway | Model Manager | 버전 비율 조회 |
| Front Gateway | Queue Broker | enqueue · 순번 조회 |
| Lease Renewer | Queue Broker | lease 갱신 |
| Membership | Monitoring | 장애 · 복구 알림 |
| Membership | Queue Broker | lease 회수 |
| Membership | Worker Agent | 인출 중지 |
| Model Runtime | Front Gateway | stream |
| Model Runtime | Model Manager | 가중치 적재 |
| Monitoring | Engine Scheduler | 세션 · GPU · TTFT 수집 |
| Monitoring | Model Manager | 응답 샘플 전달 |
| Monitoring | Queue Broker | 큐 길이 수집 |
| Queue Broker | Front Gateway | 실패 통지 |
| Worker Agent | Credit Manager | 인출 중지 |
| Worker Agent | Membership | 하트비트 · 상태 보고 |
| Worker Agent | Model Manager | 설정 감지 |
| Worker Agent | Model Runtime | 재적재 지시 |

### 1.4 Membership 차일드

| 요소 | 스테레오타입(전략) |
|---|---|
| Health Detector | R3 |
| Leader Lock | R3 보완 |
| Lease Reclaimer | R3 |
| Notice Handler | R3 |
| Recovery Notifier | R3 |
| Replenisher | R3 |
| AWS (스팟) (외부) | - |
| Monitoring (외부) | monitor |
| Queue Broker (외부) | C2 |
| Worker x (4 + 1) (외부) | C2 |

| 보내는 쪽 | 받는 쪽 | 내용 |
|---|---|---|
| AWS (스팟) | Notice Handler | 회수 통지 |
| AWS (스팟) | Replenisher | 기동 완료 ※추가 |
| Health Detector | Leader Lock | 리더 확인 |
| Health Detector | Lease Reclaimer | 제외 대상 |
| Health Detector | Replenisher | 재보충 지시 · 합류 확인 |
| Lease Reclaimer | Queue Broker | lease 회수 |
| Lease Reclaimer | Recovery Notifier | 재분배 완료 |
| Monitoring | Queue Broker | 큐 길이 수집 |
| Monitoring | Worker x (4 + 1) | 세션 · GPU · TTFT 수집 |
| Notice Handler | Health Detector | 회수 예정 알림 |
| Notice Handler | Worker x (4 + 1) | 인출 중지 |
| Recovery Notifier | Monitoring | 장애 · 복구 알림 |
| Replenisher | AWS (스팟) | 재생성 |
| Replenisher | Recovery Notifier | 합류 완료 |
| Worker x (4 + 1) | Health Detector | 하트비트 · 상태 보고 |
| Worker x (4 + 1) | Queue Broker | pull |

## 2. 패키지와 클래스

- Java 패키지는 모듈명에서 하이픈을 뺀 이름을 쓴다(front-gateway → `frontgateway`, model-manager → `modelmanager`, fake-aws → `fakeaws`).
- WebUI 패키지의 클래스(WebUI, AdminConsole, SseClient)는 정적 HTML + JavaScript 모듈로, 같은 이름의 JS 파일·함수로 만든다. SseClient는 `fetch()` POST 응답을 SSE 형식으로 읽는다.
- 설정 파일(«xml»)은 각 모듈의 `application.yml` 설정 키와 `XxxProperties`로 만든다.

### ApiClient  →  `com.llmserving.client.apiclient`

**ApiClient** (boundary) — 외부 프로그램용 질문 API 클라이언트
- 오퍼레이션 `ask(text: String): Stream<StreamChunk>`

### Common  →  `com.llmserving.common`

**QualitySample** (entity) — 운영 중 응답 품질 샘플
- 속성 `requestId: String`
- 속성 `versionTag: String`
- 속성 `score: double`

**Request** (entity) — 질문 1건. 요청 ID와 버전 태그로 큐, lease, 스트림을 잇는다
- 속성 `requestId: String`
- 속성 `versionTag: String`
- 속성 `inputTokens: int`
- 속성 `createdAt: long`
- 속성 `prompt: String` — 질문 본문. 실제 엔진에 넘길 입력 (mock은 길이만 사용)

**StreamChunk** (entity) — 워커가 밀어 보내는 토큰 조각. 요청 ID와 순번으로 중복을 거른다
- 속성 `requestId: String`
- 속성 `seq: int`
- 속성 `token: String`
- 속성 `isFirst: boolean`
- 속성 `isLast: boolean` — 마지막 조각. Front Gateway가 SSE를 닫는 근거

**WorkerStatus** (entity) — 하트비트에 실리는 워커 상태
- 속성 `workerId: String`
- 속성 `versionTag: String`
- 속성 `state: String`
- 속성 `unstartedTokens: int`
- 속성 `selfTestPassed: boolean`

### FrontGateway  →  `com.llmserving.frontgateway`

> VersionTagger가 Model Config의 v2 비율대로 새 요청에 태그 / StreamRelay는 FailureNotifier를 구현 / lease 경계의 중복 결과는 DuplicateFilter가 요청 ID와 순번으로 걸러냄

**DuplicateFilter** (control) — lease 경계의 중복 결과를 요청 ID와 순번으로 걸러냄 (C2 표준 보완)
- 오퍼레이션 `accept(chunk: StreamChunk): boolean`

**ModelConfigClient** (boundary) — Model Manager에서 v2 비율 조회
- 오퍼레이션 `getRatio(): double`

**QuestionController** (control) — 질문 접수 진입점. SSE 연결을 열고 요청 ID, 버전 태그를 붙여 enqueue
- 오퍼레이션 `receive(text: String): SseSession`

**QueueBrokerClient** (boundary) — Queue Broker 접수 API 호출
- 오퍼레이션 `enqueue(request: Request): int`
- 오퍼레이션 `getPosition(requestId: String): int`

**RequestIdIssuer** (control) — 요청 ID 발급
- 오퍼레이션 `issue(): String`

**SseSession** (entity) — 요청 하나의 SSE 연결 상태
- 속성 `sessionId: String`
- 속성 `requestId: String`
- 속성 `lastSeq: int`

**StreamRelay** (control) — 순번 안내, 토큰, 실패 응답을 SSE 세션으로 전달
- 오퍼레이션 `notifyPosition(requestId: String, position: int): void`
- 오퍼레이션 `onChunk(chunk: StreamChunk): void`
- 오퍼레이션 `notifyFailure(requestId: String): void`

**VersionTagger** (control) — v2 비율대로 새 요청에 v1/v2 태그
- 오퍼레이션 `tag(request: Request): String`

### Membership  →  `com.llmserving.membership`

> 제어기는 2개로 복제, LeaderLock을 가진 1개만 조치 / MemberRegistry는 하트비트로 재구성되므로 데이터 복제 불필요 / 회수 통지, 기동 완료는 두 제어기 모두 받고, 기동 완료는 리더만 처리(나머지는 무시) / 락 만료 순간 두 제어기가 동시에 재보충해도 중복 방지 키(workerId 기반)로 두 번째 요청은 무시 / 시간 값은 membership-config에서 읽음

### Membership/Detection  →  `com.llmserving.membership.detection`

**HealthDetector** (control) — 하트비트로 장애 감지, 제외, 재보충 지시, 합류 확인
- 오퍼레이션 `onHeartbeat(status: WorkerStatus): void`
- 오퍼레이션 `onReclaimScheduled(workerId: String): void`
- 오퍼레이션 `detectFailures(): void`
- 오퍼레이션 `confirmJoin(workerId: String): void`

**Member** (entity) — 워커 1대의 멤버십 상태
- 속성 `workerId: String`
- 속성 `state: String`
- 속성 `versionTag: String`
- 속성 `lastHeartbeat: long`

**MemberRegistry** (control) — 워커 목록. 하트비트로 재구성되므로 별도 데이터 복제 불필요
- 오퍼레이션 `upsert(status: WorkerStatus): void`
- 오퍼레이션 `exclude(workerId: String): void`
- 오퍼레이션 `activeCount(): int`

**NoticeHandler** (control) — 스팟 회수 통지 수신, 인출 중지(멱등)
- 오퍼레이션 `onReclaimNotice(workerId: String): void`

### Membership/Leader  →  `com.llmserving.membership.leader`

**LeaderLock** (control) — 제어기 2개 중 락을 가진 1개만 조치 (복제, 리더 락)
- 속성 `holderId: String`
- 속성 `expiresAt: long`
- 오퍼레이션 `tryAcquire(): boolean`
- 오퍼레이션 `isLeader(): boolean`
- 오퍼레이션 `renew(): void`

**membership-config** (설정 파일) — 하트비트 제한 시간, 락 TTL
- 속성 `heartbeatTimeoutSec: int`
- 속성 `lockTtlSec: int`

### Membership/Recovery  →  `com.llmserving.membership.recovery`

**LeaseReclaimer** (control) — 제외된 워커의 lease 회수, 남은 4대로 재분배
- 오퍼레이션 `reclaim(workerId: String): void`

**RecoveryNotifier** (control) — T서비스, T재보충 기록과 운영자 알림
- 오퍼레이션 `onRedistributed(workerId: String): void`
- 오퍼레이션 `onJoined(workerId: String): void`

**RecoveryRecord** (entity) — 장애 1건의 복구 시간 기록
- 속성 `workerId: String`
- 속성 `detectedAt: long`
- 속성 `tServiceSec: int`
- 속성 `tReplenishSec: int`

**Replenisher** (control) — 대체 서버 요청과 합류 처리 (R0 경로)
- 오퍼레이션 `requestReplacement(workerId: String): void`
- 오퍼레이션 `onInstanceReady(instanceId: String): void`
- 오퍼레이션 `onJoinConfirmed(workerId: String): void`

**SpotInstanceClient** (boundary) — AWS 스팟 인스턴스 재생성 호출
- 오퍼레이션 `requestInstance(idempotencyKey: String): String`

### ModelManager  →  `com.llmserving.modelmanager`

> 유지보수성 (QAS 5, U1 단점 3) / - 모델 교체는 model-config 1곳만 수정 / - 사다리 단계 추가는 LadderStage 자식 클래스 1개 추가 / - 롤백도 ModelConfig를 거쳐 교체와 같은 경로 / - 단계별 재검증은 QualityGate 한 곳에 모음

**Fp8Stage** (entity) — 같은 8B를 FP8(W8A8)로
- (멤버 없음: 부모 클래스를 상속만 한다)

**GateResult** (entity) — 게이트 판정 결과
- 속성 `passed: boolean`
- 속성 `qualityScore: double`
- 속성 `sessionsPerDollar: double`

**LadderStage** (entity) — 모델 축소 사다리의 한 단계 (U1). 단계 추가는 자식 클래스 추가
- 속성 `name: String`
- 속성 `precision: String`
- 속성 `weightUri: String`
- 속성 `kvGbPerGpu: double`

**ModelConfig** (control) — 활성 모델, 단계, v2 비율의 단일 기준점
- 오퍼레이션 `getRatio(): double`
- 오퍼레이션 `setTarget(version: ModelVersion): void`
- 오퍼레이션 `onDrainStart(workerId: String): void`
- 오퍼레이션 `onReady(workerId: String): void`
- 오퍼레이션 `switchTo(stage: LadderStage): void`
- 오퍼레이션 `currentStage(): LadderStage`

**ModelStoreDAO** (boundary) — 단계별 가중치 원본(S3) 접근
- 오퍼레이션 `loadWeights(stage: LadderStage): byte[]`
- 오퍼레이션 `listStages(): List<LadderStage>`

**ModelSwapController** (control) — 운영자 교체 요청 진입점. 설정 1곳(model-config)만 수정
- 오퍼레이션 `requestSwap(model: String, stage: String): void`
- 오퍼레이션 `getSwapStatus(): String`

**ModelVersion** (entity) — 모델 이름과 버전
- 속성 `modelName: String`
- 속성 `version: String`

**QualityGate** (control) — 후보 단계 품질, 처리량 판정과 운영 중 품질 감시
- 오퍼레이션 `evaluate(stage: LadderStage): GateResult`
- 오퍼레이션 `observe(sample: QualitySample): void`

**RollbackManager** (control) — 품질 이상 시 U0로 되돌림
- 오퍼레이션 `rollbackToU0(): void`

**SmallStage** (entity) — 같은 계열 3B 소형 모델
- (멤버 없음: 부모 클래스를 상속만 한다)

**U0Stage** (entity) — 8B 원정밀. 롤백 목표
- (멤버 없음: 부모 클래스를 상속만 한다)

**VersionRatio** (entity) — v2 비율 = v2 준비 워커 수 / 인출 중인 워커 수
- 속성 `readyWorkers: int`
- 속성 `pullingWorkers: int`
- 오퍼레이션 `ratio(): double`

**model-config** (설정 파일) — 활성 모델, 사다리 단계. QAS 5의 설정 파일 1곳
- 속성 `activeModel: String`
- 속성 `activeStage: String`
- 속성 `targetStage: String`

### Monitoring  →  `com.llmserving.monitoring`

**AlertEvent** (entity) — 알림 한 건
- 속성 `kind: String`
- 속성 `message: String`
- 속성 `at: long`

**AlertService** (control) — 장애, 복구 알림을 운영자 화면에 제공
- 오퍼레이션 `raise(event: AlertEvent): void`
- 오퍼레이션 `getAlerts(): List<AlertEvent>`

**Metric** (entity) — 지표 한 건
- 속성 `name: String`
- 속성 `value: double`
- 속성 `at: long`

**MetricsCollector** (control) — 큐 길이, 세션, GPU, TTFT 수집
- 오퍼레이션 `collectQueueLength(): void`
- 오퍼레이션 `collectWorkerMetrics(): void`
- 오퍼레이션 `query(name: String): List<Metric>`

**SampleForwarder** (control) — 응답 샘플과 지표를 Quality Gate로 전달
- 오퍼레이션 `forward(sample: QualitySample): void`

### QueueBroker  →  `com.llmserving.broker`

> 상한값(동시 실행 4대분, 재시도 2회, lease TTL)은 broker-config에서 읽음 / 실패 통지는 FailureNotifier 인터페이스로 Front Gateway에 의존하지 않음 / LeaseManager 호출자: Worker CreditManager(pull), LeaseRenewer(renew), Membership LeaseReclaimer(reclaim)

### QueueBroker/Admission  →  `com.llmserving.broker.admission`

**AdmissionController** (control) — 접수 창구. 거절하지 않고 적재 후 복제 확인되면 순번 응답
- 오퍼레이션 `enqueue(request: Request): int`
- 오퍼레이션 `getPosition(requestId: String): int`

### QueueBroker/Lease  →  `com.llmserving.broker.lease`

**Lease** (entity) — 실행 중 작업 1건의 점유권
- 속성 `leaseId: String`
- 속성 `requestId: String`
- 속성 `workerId: String`
- 속성 `expiresAt: long`
- 속성 `item: QueueItem` — 점유한 작업. 워커에 본문을 넘기고, 만료 시 RetryCounter, 회수 시 requeue에 넘김

**LeaseLimiter** (control) — 전체 동시 실행을 4대분 상한으로 제한 (R3)
- 속성 `concurrencyCap: int`
- 속성 `activeLeases: int`
- 오퍼레이션 `allow(requested: int): int`
- 오퍼레이션 `release(): void`

**LeaseManager** (control) — pull, lease 발급, 갱신, 만료, 회수
- 오퍼레이션 `pull(workerId: String, versionTag: String, n: int): List<Lease>`
- 오퍼레이션 `remainingFor(versionTag: String): int` — 공용 큐에 남은 같은 버전 작업 수. pull 응답에 실어 보낸다 (sd6, 5절)
- 오퍼레이션 `renew(leaseId: String): void`
- 오퍼레이션 `complete(leaseId: String): void`
- 오퍼레이션 `expireOverdue(): void`
- 오퍼레이션 `reclaim(workerId: String): void`

**broker-config** (설정 파일) — lease TTL, 동시 실행 상한, 재시도 상한
- 속성 `leaseTtlSec: int`
- 속성 `concurrencyCap: int`
- 속성 `retryLimit: int`

### QueueBroker/Queue  →  `com.llmserving.broker.queue`

**QuarantineQueue** (control) — 재시도 상한에 닿은 요청 격리 (R3 보완)
- 오퍼레이션 `isolate(item: QueueItem): void`
- 오퍼레이션 `list(): List<QueueItem>`

**QueueItem** (entity) — 큐에 들어간 요청과 재시도 횟수
- 속성 `request: Request`
- 속성 `enqueuedAt: long`
- 속성 `retryCount: int`

**SharedQueue** (control) — 공용 대기열. 버전 일치 작업만 인출
- 오퍼레이션 `push(item: QueueItem): int`
- 오퍼레이션 `pullMatching(versionTag: String, k: int): List<QueueItem>`
- 오퍼레이션 `requeue(item: QueueItem): void`
- 오퍼레이션 `positionOf(requestId: String): int`
- 오퍼레이션 `length(): int`
- 오퍼레이션 `countMatching(versionTag: String): int` — 버전 태그가 같은 대기 작업 수 (sd6, 5절)

### QueueBroker/Replication  →  `com.llmserving.broker.replication`

**BrokerReplica** (control) — 대기 복제본. 주 브로커 소실 시 쿼럼 기반 승격, 옛 주 브로커 fencing
- 오퍼레이션 `apply(change: ChangeRecord): void`
- 오퍼레이션 `detectPrimaryLoss(): boolean`
- 오퍼레이션 `promote(): void`
- 오퍼레이션 `fenceOldPrimary(): void`

**ChangeRecord** (entity) — 복제 단위 변경 기록
- 속성 `seq: long`
- 속성 `kind: String`
- 속성 `payload: String`
- 속성 `epoch: long` — 주 브로커 세대 번호. 승격 시 +1, 낮은 epoch 메시지는 거부 (fencing, sd5)

**Replicator** (control) — 큐와 lease 변경을 복제본에 동기 복제
- 오퍼레이션 `replicate(change: ChangeRecord): boolean`

### QueueBroker/Retry  →  `com.llmserving.broker.retry`

**RetryCounter** (control) — 만료 횟수를 세어 재투입 또는 격리
- 오퍼레이션 `onExpired(item: QueueItem): void`

**RetryPolicy** (control) — 재시도 상한 판정 (상한은 설정값)
- 속성 `retryLimit: int`
- 오퍼레이션 `shouldRetry(item: QueueItem): boolean`

**FailureNotifier** (인터페이스) — 격리된 요청의 실패 통지 포트. Front Gateway가 구현
- 오퍼레이션 `notifyFailure(requestId: String): void`

### WebUI  →  `client/src/main/resources/static` (Java 패키지 없음)

**AdminConsole** (boundary) — 운영자 화면: 모델 교체 요청, 처리 현황, 알림
- 오퍼레이션 `requestModelSwap(model: String, stage: String): void`
- 오퍼레이션 `showSwapStatus(): void`
- 오퍼레이션 `showAlerts(): void`
- 오퍼레이션 `showMetrics(): void`

**SseClient** (boundary) — SSE 연결로 순번 안내와 토큰 스트림을 받는다
- 오퍼레이션 `open(question: String): void`
- 오퍼레이션 `onEvent(event: String): void`
- 오퍼레이션 `close(): void`

**WebUI** (boundary) — 이용자 질문 화면
- 오퍼레이션 `sendQuestion(text: String): void`
- 오퍼레이션 `showQueuePosition(position: int): void`
- 오퍼레이션 `showToken(chunk: StreamChunk): void`
- 오퍼레이션 `showFailure(requestId: String): void`

### Worker  →  `com.llmserving.worker`

> 유지보수성(T3 단점 3 대응) / - 호출 순서는 런타임과 같음: EngineScheduler -> BudgetPolicy -> BudgetGuard / - BudgetPolicy는 추상 클래스. budgetFor()는 고정 메서드라 정책이 바뀌어도 Guard의 하한, 상한이 항상 적용됨 / - 새 정책은 propose()만 재정의 (기본: BudgetController) / - 모델, GPU가 바뀌면 budget-config만 수정 / - 크레딧과 lease 갱신은 EngineEventListener로 구독해 엔진에 직접 묶이지 않음 / (생략: Monitoring MetricsCollector가 EngineScheduler 수집)

### Worker/Agent  →  `com.llmserving.worker.agent`

**WorkerAgent** (control) — 하트비트, 인출 중지, 설정 감지, 재적재 지시, 부팅 시 자체 적재와 생성 시험
- 오퍼레이션 `sendHeartbeat(): void`
- 오퍼레이션 `stopPull(): void`
- 오퍼레이션 `onConfigChanged(): void`
- 오퍼레이션 `reload(stage: LadderStage): void`
- 오퍼레이션 `selfTest(): boolean`

### Worker/Budget  →  `com.llmserving.worker.budget`

**Backlog** (entity) — 미시작 Prefill 토큰, 진행 중 Decode 수
- 속성 `unstartedPrefill: int`
- 속성 `decodeCount: int`

**BudgetController** (control) — 밀린 Prefill이 많으면 B를 키우는 기본 정책 (Budget Controller)
- 오퍼레이션 `propose(backlog: Backlog): int`

**BudgetGuard** (control) — 예산 정책이 낸 B를 하한과 상한 사이로 자름. 정책이 바뀌어도 항상 적용
- 속성 `bMin: int`
- 속성 `bMax: int`
- 오퍼레이션 `clamp(candidate: int): int`

**BudgetPolicy** (control) — 예산 정책 추상 클래스(T3). budgetFor()는 고정 메서드로 propose() 결과를 항상 BudgetGuard로 자른다. 구현 클래스는 propose()만 바꾼다
- 오퍼레이션 `budgetFor(backlog: Backlog): int` (final, 재정의 금지)
- 오퍼레이션 `propose(backlog: Backlog): int` (abstract)

**budget-config** (설정 파일) — B 하한, 상한, 목표 첫 토큰 시간. 모델, GPU별로 설정만 바꾼다
- 속성 `bMin: int`
- 속성 `bMax: int`
- 속성 `targetTtftMs: int`

### Worker/Credit  →  `com.llmserving.worker.credit`

**Capacity** (entity) — 남은 슬롯, KV, 미시작 토큰
- 속성 `freeSlots: int`
- 속성 `freeKvGb: double`
- 속성 `unstartedTokens: int`

**CreditManager** (control) — 실제 여유로 크레딧을 계산해 그만큼만 pull (C2)
- 오퍼레이션 `computeCredit(): int`
- 오퍼레이션 `pullJobs(): void`
- 오퍼레이션 `setVersion(versionTag: String): void`
- 오퍼레이션 `stopPull(): void`
- 오퍼레이션 `onFirstToken(requestId: String, unstartedTokens: int): void`
- 오퍼레이션 `onProgress(requestId: String): void`
- 오퍼레이션 `onCompleted(requestId: String): void` — 엔진 완료 이벤트. 요청 ID로 lease를 찾아 complete ack, 크레딧 반환
- 오퍼레이션 `remainingForVersion(): int` — 공용 큐에 남은 내 버전 작업 수. 크레딧이 0이어도 인출 없이(n = 0 pull) 확인한다 (sd6, 5절)

### Worker/LeaseRenewal  →  `com.llmserving.worker.leaserenewal`

**LeaseRenewer** (control) — 진척이 보고된 요청의 lease만 갱신 (C2 단점 4 보완)
- 오퍼레이션 `onFirstToken(requestId: String, unstartedTokens: int): void`
- 오퍼레이션 `onProgress(requestId: String): void`
- 오퍼레이션 `onCompleted(requestId: String): void` — 완료된 요청은 더 갱신하지 않음
- 오퍼레이션 `renewActive(): void`

### Worker/Runtime  →  `com.llmserving.worker.runtime`

**ModelRuntime** (control) — GPU에 가중치를 올리고 스텝을 실행
- 오퍼레이션 `execute(plan: StepPlan): StepResult`
- 오퍼레이션 `loadWeights(stage: LadderStage): void`
- 오퍼레이션 `generationTest(): boolean`

**StepResult** (entity) — 스텝 결과 토큰과 Prefill을 끝낸 요청
- 속성 `chunks: List<StreamChunk>`
- 속성 `prefillDoneIds: List<String>`
- 속성 `finishedIds: List<String>` — 이번 스텝에서 생성이 끝난 요청 (완료 ack와 SSE 종료의 근거)

**StreamPublisher** (boundary) — 토큰을 Front Gateway로 비동기 푸시
- 오퍼레이션 `publish(chunk: StreamChunk): void`

### Worker/Scheduling  →  `com.llmserving.worker.scheduling`

**EngineScheduler** (control) — 매 스텝 Decode 전부 + Prefill 조각(합계 ≤ B)으로 구성 (T3)
- 오퍼레이션 `submit(job: RequestState): void`
- 오퍼레이션 `buildStep(): StepPlan`
- 오퍼레이션 `onStepDone(result: StepResult): void`
- 오퍼레이션 `subscribe(listener: EngineEventListener): void`
- 오퍼레이션 `freeCapacity(): Capacity`

**RequestState** (entity) — 워커 안에서 진행 중인 요청 상태
- 속성 `requestId: String`
- 속성 `versionTag: String`
- 속성 `unstartedTokens: int`
- 속성 `phase: String`
- 속성 `prompt: String` — 질문 본문 (Request에서 받음)

**StepPlan** (entity) — 한 스텝에 넣을 Decode와 Prefill 조각
- 속성 `decodeIds: List<String>`
- 속성 `prefillChunks: Map<String,int>`
- 속성 `budget: int`
- 속성 `prompts: Map<String,String>` — 이번 스텝에서 Prefill을 처음 시작하는 요청의 본문 (요청 ID → 본문)

**EngineEventListener** (인터페이스) — 엔진 이벤트 구독 포트. 크레딧과 lease 갱신이 엔진에 직접 묶이지 않게 함 (T3 단점 3 보완)
- 오퍼레이션 `onFirstToken(requestId: String, unstartedTokens: int): void`
- 오퍼레이션 `onProgress(requestId: String): void`
- 오퍼레이션 `onCompleted(requestId: String): void` — 요청 생성 완료 (`StepResult.finishedIds`)

## 3. 클래스 관계

같은 컴포넌트 안의 관계는 메서드 호출로, 다른 컴포넌트로 가는 관계는 `CLAUDE.md` 3절의 Port와 HTTP로 만든다.

### ApiClient

| 클래스 | 관계 | 대상 | 대상 컴포넌트 | 설명 |
|---|---|---|---|---|
| ApiClient | 사용 | QuestionController | **FrontGateway** (다른 앱) |  |

### WebUI

| 클래스 | 관계 | 대상 | 대상 컴포넌트 | 설명 |
|---|---|---|---|---|
| WebUI | 사용 | SseClient | WebUI |  |
| SseClient | 사용 | QuestionController | **FrontGateway** (다른 앱) | SSE |
| AdminConsole | 사용 | ModelSwapController | **ModelManager** (다른 앱) | 교체 요청 · 상태 조회 |
| AdminConsole | 사용 | AlertService | **Monitoring** (다른 앱) | 알림 조회 |
| AdminConsole | 사용 | MetricsCollector | **Monitoring** (다른 앱) | 현황 조회 |

### FrontGateway

| 클래스 | 관계 | 대상 | 대상 컴포넌트 | 설명 |
|---|---|---|---|---|
| QuestionController | 사용 | RequestIdIssuer | FrontGateway |  |
| QuestionController | 사용 | VersionTagger | FrontGateway |  |
| QuestionController | 사용 | QueueBrokerClient | FrontGateway | enqueue · 순번 조회 |
| QuestionController | 사용 | StreamRelay | FrontGateway |  |
| QuestionController | 사용 | Request | Common | create |
| VersionTagger | 사용 | ModelConfigClient | FrontGateway | 비율 조회 |
| StreamRelay | 사용 | DuplicateFilter | FrontGateway |  |
| StreamRelay | 포함 | SseSession | FrontGateway |  |
| StreamRelay | 구현 | FailureNotifier | **QueueBroker** (다른 앱) |  |
| QueueBrokerClient | 사용 | AdmissionController | **QueueBroker** (다른 앱) |  |
| ModelConfigClient | 사용 | ModelConfig | **ModelManager** (다른 앱) | 버전 비율 조회 |

### QueueBroker

| 클래스 | 관계 | 대상 | 대상 컴포넌트 | 설명 |
|---|---|---|---|---|
| AdmissionController | 사용 | SharedQueue | QueueBroker | 적재 · 순번 조회 |
| SharedQueue | 사용 | Replicator | QueueBroker | 변경 복제 |
| SharedQueue | 포함 | QueueItem | QueueBroker |  |
| QueueItem | 포함 | Request | Common |  |
| LeaseManager | 사용 | SharedQueue | QueueBroker | 버전 일치 인출 |
| LeaseManager | 사용 | LeaseLimiter | QueueBroker | 4대분 상한 확인 |
| LeaseManager | 포함 | Lease | QueueBroker |  |
| Lease | 사용 | QueueItem | QueueBroker | 점유한 작업 |
| LeaseManager | 사용 | RetryCounter | QueueBroker | 만료 보고 |
| LeaseManager | 사용 | Replicator | QueueBroker | lease 상태 |
| LeaseLimiter | 사용 | broker-config | QueueBroker | read |
| RetryCounter | 사용 | RetryPolicy | QueueBroker |  |
| RetryPolicy | 사용 | broker-config | QueueBroker | read |
| RetryCounter | 사용 | SharedQueue | QueueBroker | 재투입 |
| RetryCounter | 사용 | QuarantineQueue | QueueBroker | 격리 |
| RetryCounter | 사용 | FailureNotifier | QueueBroker | 실패 통지 |
| Replicator | 사용 | BrokerReplica | QueueBroker | 동기 복제 |
| Replicator | 사용 | ChangeRecord | QueueBroker |  |
| BrokerReplica | 사용 | ChangeRecord | QueueBroker |  |

### Worker

| 클래스 | 관계 | 대상 | 대상 컴포넌트 | 설명 |
|---|---|---|---|---|
| WorkerAgent | 사용 | CreditManager | Worker | 인출 중지 · 버전 설정 |
| WorkerAgent | 사용 | ModelRuntime | Worker | 재적재 지시 |
| WorkerAgent | 사용 | HealthDetector | **Membership** (다른 앱) | 하트비트 · 상태 보고 |
| WorkerAgent | 사용 | ModelConfig | **ModelManager** (다른 앱) | 설정 감지 · 준비 보고 |
| WorkerAgent | 사용 | WorkerStatus | Common |  |
| CreditManager | 사용 | LeaseManager | **QueueBroker** (다른 앱) | pull |
| CreditManager | 사용 | EngineScheduler | Worker | 여유 조회 · 작업 투입 |
| CreditManager | 사용 | Capacity | Worker |  |
| CreditManager | 구현 | EngineEventListener | Worker |  |
| LeaseRenewer | 구현 | EngineEventListener | Worker |  |
| EngineScheduler | 사용 | EngineEventListener | Worker | 첫 토큰 · 진척 · 완료 이벤트 |
| EngineScheduler | 포함 | RequestState | Worker |  |
| EngineScheduler | 사용 | StepPlan | Worker |  |
| EngineScheduler | 사용 | BudgetPolicy | Worker | 스텝 예산 요청 |
| EngineScheduler | 사용 | ModelRuntime | Worker | 스텝 실행 |
| EngineScheduler | 사용 | Backlog | Worker |  |
| BudgetPolicy | 사용 | BudgetGuard | Worker | B 후보 자르기 |
| BudgetGuard | 사용 | budget-config | Worker | read |
| ModelRuntime | 사용 | StepResult | Worker |  |
| ModelRuntime | 사용 | StreamPublisher | Worker |  |
| ModelRuntime | 사용 | ModelStoreDAO | **ModelManager** (다른 앱) | 가중치 적재 |
| StreamPublisher | 사용 | StreamRelay | **FrontGateway** (다른 앱) | 토큰 푸시 |
| LeaseRenewer | 사용 | LeaseManager | **QueueBroker** (다른 앱) | lease 갱신 |
| BudgetController | 상속 | BudgetPolicy | Worker |  |
| BudgetPolicy | 사용 | Backlog | Worker |  |

### Membership

| 클래스 | 관계 | 대상 | 대상 컴포넌트 | 설명 |
|---|---|---|---|---|
| NoticeHandler | 사용 | WorkerAgent | **Worker** (다른 앱) | 인출 중지 |
| NoticeHandler | 사용 | HealthDetector | Membership | 회수 예정 알림 |
| HealthDetector | 사용 | LeaderLock | Membership | 리더 확인 |
| HealthDetector | 사용 | MemberRegistry | Membership |  |
| MemberRegistry | 포함 | Member | Membership |  |
| HealthDetector | 사용 | LeaseReclaimer | Membership | 제외 대상 |
| HealthDetector | 사용 | Replenisher | Membership | 재보충 지시 · 합류 확인 |
| HealthDetector | 사용 | membership-config | Membership | read |
| LeaderLock | 사용 | membership-config | Membership | read |
| LeaseReclaimer | 사용 | LeaseManager | **QueueBroker** (다른 앱) | lease 회수 |
| LeaseReclaimer | 사용 | RecoveryNotifier | Membership | 재분배 완료 |
| Replenisher | 사용 | SpotInstanceClient | Membership | 재생성 |
| Replenisher | 사용 | RecoveryNotifier | Membership | 합류 완료 |
| RecoveryNotifier | 사용 | RecoveryRecord | Membership |  |
| RecoveryNotifier | 사용 | AlertService | **Monitoring** (다른 앱) | 장애 · 복구 알림 |
| HealthDetector | 사용 | WorkerStatus | Common |  |

### ModelManager

| 클래스 | 관계 | 대상 | 대상 컴포넌트 | 설명 |
|---|---|---|---|---|
| ModelSwapController | 사용 | ModelConfig | ModelManager | 교체 요청 |
| ModelConfig | 사용 | model-config | ModelManager | read · update |
| ModelConfig | 사용 | QualityGate | ModelManager | 통과 단계만 허용 |
| ModelConfig | 포함 | VersionRatio | ModelManager |  |
| ModelConfig | 포함 | ModelVersion | ModelManager |  |
| ModelVersion | 포함 | LadderStage | ModelManager |  |
| U0Stage | 상속 | LadderStage | ModelManager |  |
| Fp8Stage | 상속 | LadderStage | ModelManager |  |
| SmallStage | 상속 | LadderStage | ModelManager |  |
| QualityGate | 사용 | GateResult | ModelManager |  |
| QualityGate | 사용 | ModelStoreDAO | ModelManager | 후보 평가 |
| QualityGate | 사용 | RollbackManager | ModelManager | 운영 중 품질 이상 |
| RollbackManager | 사용 | ModelConfig | ModelManager | U0로 전환 |
| ModelStoreDAO | 사용 | LadderStage | ModelManager |  |

### Monitoring

| 클래스 | 관계 | 대상 | 대상 컴포넌트 | 설명 |
|---|---|---|---|---|
| MetricsCollector | 사용 | EngineScheduler | **Worker** (다른 앱) | 세션 · GPU · TTFT 수집 |
| MetricsCollector | 사용 | SharedQueue | **QueueBroker** (다른 앱) | 큐 길이 수집 |
| MetricsCollector | 사용 | Metric | Monitoring |  |
| SampleForwarder | 사용 | QualityGate | **ModelManager** (다른 앱) | 응답 샘플 · 지표 전달 |
| SampleForwarder | 사용 | QualitySample | Common |  |
| AlertService | 사용 | AlertEvent | Monitoring |  |
| MetricsCollector | 사용 | SampleForwarder | Monitoring | 응답 샘플 |

- EA의 `StreamRelay → FailureNotifier (구현)`은 앱이 달라 코드에서 직접 구현할 수 없다. broker의 `FailureNotifier` 인터페이스는 EA대로 `broker.retry`에 두고, 구현 `FailureNotifierRestAdapter`(broker `adapter`)가 front-gateway의 StreamController → `StreamRelay.notifyFailure()`를 호출한다.
## 4. 시퀀스와 오퍼레이션 대응표 (sd1~sd6)

EA 시퀀스의 메시지는 한국어 설명으로 되어 있다. 아래 표의 "구현" 열이 실제로 호출할 오퍼레이션이다.
- `응답`: 앞 호출의 반환값이다. 별도 오퍼레이션을 만들지 않는다.
- `내부 처리`: 같은 클래스 안의 처리다. 표에 적힌 오퍼레이션 안에서 한다.
- `(HTTP, XxxPort)`: 다른 앱 호출이다. `CLAUDE.md` 3절 Port 목록대로 만든다.

### sd1. 순번 안내와 크레딧 Pull (QAS 1)

> QAS 1 폭주 동시 처리 / - 처리 한도를 넘어도 오류 대신 대기 순번을 안내 (긴 입력 1초 이내) / - 짧은 질문 30초, 긴 입력 3분 이내 첫 토큰 / - 에러 비율 0.1% 이하

> C2 공용 대기열 + 크레딧 Pull / - 워커가 인출 시점의 실제 여유만큼만 당겨 감 / - 보고 지연이 없어 OOM, 거절 후 재처리가 줄고 실패율이 낮아짐 / - 동시 실행은 4대분으로 제한 (R3)

| # | 보내는 쪽 → 받는 쪽 | EA 메시지 | 구현 |
|---|---|---|---|
| 1 | 이용자 → Web UI | 질문 전송 (긴 입력 포함) | `WebUI.sendQuestion(text)` (화면 입력) |
| 2 | Web UI → Front Gateway | 질문 요청 (SSE 연결) | `SseClient.open(question)` → `POST /api/front-gateway/questions` → `QuestionController.receive(text)`가 `SseSession`을 열고 응답을 SSE 스트림으로 유지 |
| 3 | Front Gateway → Front Gateway | 요청 ID 발급, 비율에 따른 버전 태그 | 내부 처리: `RequestIdIssuer.issue()`, `VersionTagger.tag(request)` (비율은 `ModelConfigClient.getRatio()`) |
| 4 | Front Gateway → Admission Controller | enqueue(요청 ID, 버전, 입력 토큰 수) | `QueueBrokerClient.enqueue(request)` → broker `AdmissionController.enqueue(request)` (HTTP, AdmissionPort) |
| 5 | Admission Controller → Shared Queue | 버전 태그로 적재 | `SharedQueue.push(item)` |
| 6 | Shared Queue → Admission Controller | 대기 순번 | 응답: `push()`의 반환값(순번) |
| 7 | Admission Controller → Front Gateway | 순번 n | 응답: `enqueue()`의 반환값(순번 n) |
| 8 | Front Gateway → Web UI | 대기 순번 안내 | `StreamRelay.notifyPosition(requestId, position)` → SSE 이벤트. 이후 순번 갱신은 `QueueBrokerClient.getPosition(requestId)`로 조회 |
| 9 | Web UI → 이용자 | "대기 n번째" 표시 | 화면 표시: `SseClient.onEvent(event)` → `WebUI.showQueuePosition(position)` |
| 10 | Credit Manager → Engine Scheduler | 남은 슬롯 · KV · 미시작 토큰 조회 | `EngineScheduler.freeCapacity()` |
| 11 | Engine Scheduler → Credit Manager | 현재 여유 | 응답: `Capacity` (freeSlots, freeKvGb, unstartedTokens) |
| 12 | Credit Manager → Credit Manager | 크레딧 n 계산 | 내부 처리: `CreditManager.computeCredit()` (주기 작업 `pullJobs()` 안에서) |
| 13 | Credit Manager → Lease Manager | pull(n) | `LeaseManager.pull(workerId, versionTag, n)` (HTTP, LeasePort → LeaseController) |
| 14 | Lease Manager → Lease Limiter | 동시 실행 4대분 상한 확인 | `LeaseLimiter.allow(n)` (상한 확인과 허용 수 증가를 한 번에) |
| 15 | Lease Limiter → Lease Manager | 허용 k (≤ n) | 응답: `allow()`의 반환값 k (≤ n) |
| 16 | Lease Manager → Shared Queue | 버전 일치 작업 k건 인출 | `SharedQueue.pullMatching(versionTag, k)` |
| 17 | Shared Queue → Lease Manager | 작업 k건 | 응답: `List<QueueItem>` |
| 18 | Lease Manager → Credit Manager | 작업 + lease 발급 | 응답: `pull()`의 반환값 `List<Lease>` (작업 포함) |
| 19 | Credit Manager → Engine Scheduler | 작업 투입 | `EngineScheduler.submit(job: RequestState)` |
| 20 | Engine Scheduler → Model Runtime | Prefill · Decode 스텝 실행 | `EngineScheduler.buildStep()` → `ModelRuntime.execute(plan)` → `InferenceEnginePort` (mock: `MockInferenceEngine`) |
| 21 | Model Runtime → Front Gateway | 첫 토큰 (요청 ID) | `StreamPublisher.publish(chunk)` → front-gateway StreamController → `StreamRelay.onChunk(chunk)` (HTTP, StreamPort) |
| 22 | Front Gateway → Web UI | 토큰 스트림 | `StreamRelay.onChunk(chunk)`가 `DuplicateFilter.accept(chunk)` 통과 후 SSE로 전송 |
| 23 | Web UI → 이용자 | 답변 표시 시작 | 화면 표시: `SseClient.onEvent(event)` → `WebUI.showToken(chunk)` |
| 24 | Credit Manager → Lease Manager | 완료 ack · 크레딧 반환 | `ModelRuntime.execute()`의 `StepResult.finishedIds` → `EngineScheduler.onStepDone(result)` → `EngineEventListener.onCompleted(requestId)` → `CreditManager.onCompleted(requestId)`가 lease를 찾아 `LeaseManager.complete(leaseId)` (HTTP, LeasePort). Broker는 `LeaseLimiter.release()`. 같은 스텝의 마지막 조각은 `StreamChunk.isLast = true` |
| 25 | Lease Manager → Retry Counter | 만료 보고 (요청 ID) | `LeaseManager.expireOverdue()` (주기 작업) → `RetryCounter.onExpired(item)` |
| 26 | Retry Counter → Shared Queue | 재투입 (재시도 < 상한) | `RetryPolicy.shouldRetry(item)`가 true → `SharedQueue.requeue(item)` |
| 27 | Retry Counter → Quarantine Queue | 격리 (재시도 ≥ 상한) | `RetryPolicy.shouldRetry(item)`가 false → `QuarantineQueue.isolate(item)` (실패 통지는 sd4 17번) |

### sd2. 스텝 예산과 Prefill 조각 실행 (QAS 2)

> QAS 2 첫 응답 속도 (처리 한도 80% 부하) / - 요청의 95%가 3초 이내 첫 토큰 / - 95% 지점은 긴 입력(20%)의 꼬리가 정함

> C2 결합: 미시작 토큰을 크레딧에 반영해 / 밀린 일이 적은 워커가 먼저 인출

> T3 시간 기준 분할 / - 긴 입력을 스텝마다 조각으로 나눠 읽음 / - 짧은 요청은 다음 스텝에 바로 끼어듦 / - 밀린 Prefill이 많으면 B를 키우고, Budget Guard가 하한과 상한을 지킴

| # | 보내는 쪽 → 받는 쪽 | EA 메시지 | 구현 |
|---|---|---|---|
| 1 | Credit Manager → Engine Scheduler | 긴 입력 L 투입 (약 4,000토큰) | `EngineScheduler.submit(job)` |
| 2 | Engine Scheduler → Budget Controller | 백로그 (미시작 Prefill 토큰, Decode 수) | `BudgetPolicy.budgetFor(backlog)` (final) → 구현 클래스 `BudgetController.propose(backlog)` |
| 3 | Budget Controller → Budget Guard | B 후보 (밀리면 키움) | `budgetFor()` 안에서 `BudgetGuard.clamp(candidate)` |
| 4 | Budget Guard → Engine Scheduler | 스텝 예산 B (하한~상한) | 응답: `budgetFor()`의 반환값 B (하한~상한) |
| 5 | Engine Scheduler → Engine Scheduler | Decode 전부 + L Prefill 조각 (합 ≤ B) | 내부 처리: `EngineScheduler.buildStep()` → `StepPlan` (decodeIds 전부 + prefillChunks 합계 ≤ B) |
| 6 | Engine Scheduler → Model Runtime | 스텝 실행 | `ModelRuntime.execute(plan)` |
| 7 | Model Runtime → Front Gateway | 진행 중 요청 토큰 | `StreamPublisher.publish(chunk)` → `StreamRelay.onChunk(chunk)` (HTTP, StreamPort) |
| 8 | Credit Manager → Engine Scheduler | 짧은 요청 S 투입 | `EngineScheduler.submit(job)` |
| 9 | Engine Scheduler → Engine Scheduler | 다음 스텝에 S Prefill 끼워 넣기 | 내부 처리: 다음 `buildStep()`에서 S의 Prefill을 넣음 |
| 10 | Engine Scheduler → Model Runtime | 스텝 실행 (S 전체 + L 다음 조각) | `ModelRuntime.execute(plan)` |
| 11 | Model Runtime → Front Gateway | S 첫 토큰 (L 완료 전) | `StreamPublisher.publish(chunk)` (isFirst = true) |
| 12 | Engine Scheduler → Credit Manager | S 첫 토큰 이벤트, 미시작 토큰 갱신 | `EngineEventListener.onFirstToken(requestId, unstartedTokens)` → CreditManager, LeaseRenewer가 구독 |
| 13 | Model Runtime → Engine Scheduler | L Prefill 완료 | 응답: `execute()`의 반환값 `StepResult.prefillDoneIds` → `EngineScheduler.onStepDone(result)` |
| 14 | Model Runtime → Front Gateway | L 첫 토큰 (3초 이내) | `StreamPublisher.publish(chunk)` (isFirst = true) |
| 15 | Engine Scheduler → Credit Manager | L 첫 토큰 이벤트, 미시작 토큰 갱신 | `EngineEventListener.onFirstToken(requestId, unstartedTokens)` |

### sd3. 스팟 회수 감지와 재분배 (QAS 3)

> QAS 3 서버 장애 복구 / - 회수 통지 즉시 새 요청 배정 중단 / - 서버가 사라진 뒤 5분 이내 장애 전 처리 능력 복구 / - 복구 후 운영자에게 알림

> R3 N+1 활성 여분 / - 남은 4대가 이미 4대분을 처리하므로 복구 경로에 확보, 부팅, 적재가 없음 / - T서비스: 감지 > 제외 > 재분배 (300초 목표) / - T재보충: 서버 소멸 감지 후 R0와 같은 신규 확보 경로로 시작, 따로 기록

> 위 4개 메시지(회수 통지 ~ 회수 예정 알림)는 통지가 올 때만 발생 / 통지 없는 장애는 하트비트 감지부터 같은 경로 / 제어기 2개 중 Leader Lock을 가진 1개만 진행 / (인출 중지는 멱등이라 두 제어기가 함께 보내도 무방)

| # | 보내는 쪽 → 받는 쪽 | EA 메시지 | 구현 |
|---|---|---|---|
| 1 | AWS (스팟) → Notice Handler | 스팟 회수 통지 (W5, 2분 전) | fake-aws → membership NoticeController → `NoticeHandler.onReclaimNotice(workerId)` (HTTP) |
| 2 | Notice Handler → Worker Agent | 인출 중지 (W5) | `WorkerAgent.stopPull()` (HTTP, WorkerControlPort → WorkerController). 멱등 |
| 3 | Worker Agent → Credit Manager | 새 인출 중지 (진행 중 답변은 계속 생성) | `CreditManager.stopPull()` |
| 4 | Notice Handler → Health Detector | 회수 예정 알림 (W5) | `HealthDetector.onReclaimScheduled(workerId)` |
| 5 | Health Detector → Health Detector | W5 하트비트 끊김 감지 (서버 소멸, T서비스 시작) | 내부 처리: `HealthDetector.detectFailures()` (주기 작업, `LeaderLock.isLeader()` 확인) → `MemberRegistry.exclude(workerId)` |
| 6 | Health Detector → Lease Reclaimer | 제외 대상 W5 | `LeaseReclaimer.reclaim(workerId)` |
| 7 | Health Detector → Replenisher | 재보충 지시 (서버 소멸 후) | `Replenisher.requestReplacement(workerId)` |
| 8 | Replenisher → AWS (스팟) | 대체 서버 요청 (T재보충 시작) | `SpotInstanceClient.requestInstance(idempotencyKey)` (HTTP, SpotInstancePort → fake-aws). 키는 workerId 기반 |
| 9 | Lease Reclaimer → Lease Manager | W5 lease 회수 | `LeaseManager.reclaim(workerId)` (HTTP, LeasePort → LeaseController) |
| 10 | Lease Manager → Lease Manager | 미완료 요청을 공용 큐로 반환 | 내부 처리: `reclaim()` 안에서 미완료 작업을 `SharedQueue.requeue(item)`. 서버 장애 회수이므로 RetryCounter를 거치지 않고 재시도 횟수도 올리지 않는다 |
| 11 | Credit Manager → Lease Manager | 남은 W1~W4가 인출 (4대분 처리) | `LeaseManager.pull(workerId, versionTag, n)` (남은 워커들의 평소 pull) |
| 12 | Lease Reclaimer → Recovery Notifier | 재분배 완료 (4대분 처리 확인) | `RecoveryNotifier.onRedistributed(workerId)` |
| 13 | Recovery Notifier → Monitoring | 복구 알림 (T서비스 기록) | `AlertService.raise(event)` (HTTP, AlertPort → AlertController). `RecoveryRecord.tServiceSec` 기록 |
| 14 | Web UI → Monitoring | 알림 조회 | `AdminConsole.showAlerts()` → monitoring `AlertService.getAlerts()` (HTTP 조회) |
| 15 | Monitoring → Web UI | 처리 능력 복구 알림 | 응답: `getAlerts()`의 반환값 `List<AlertEvent>` |
| 16 | Web UI → 운영자 | 알림 표시 | 화면 표시: AdminConsole |
| 17 | AWS (스팟) → Replenisher | 인스턴스 기동 완료 | fake-aws → membership NoticeController → `Replenisher.onInstanceReady(instanceId)` (HTTP) ※런타임 화살표 추가분 |
| 18 | Worker Agent → Worker Agent | 모델 적재, 생성 시험 | 내부 처리: 새 워커의 `WorkerAgent.reload(stage)` → `ModelRuntime.loadWeights(stage)`, `WorkerAgent.selfTest()` → `ModelRuntime.generationTest()` |
| 19 | Worker Agent → Health Detector | 상태 보고: 시험 통과 | `WorkerAgent.sendHeartbeat()` → membership HeartbeatController → `HealthDetector.onHeartbeat(status)` (HTTP, HeartbeatPort. `WorkerStatus.selfTestPassed = true`) |
| 20 | Health Detector → Replenisher | 합류 확인 | `HealthDetector.confirmJoin(workerId)` → `Replenisher.onJoinConfirmed(workerId)` |
| 21 | Replenisher → Recovery Notifier | 합류 완료 (다시 5대) | `RecoveryNotifier.onJoined(workerId)` |
| 22 | Recovery Notifier → Monitoring | T재보충 기록 | `AlertService.raise(event)` (HTTP, AlertPort). `RecoveryRecord.tReplenishSec` 기록 |

### sd4. 요청 유발 장애 격리 (QAS 1, 3)

> QAS 1 에러율 0.1% 이하, QAS 3 처리 능력 유지 / - 엔진을 죽이는 요청이 큐로 돌아가 워커를 연달아 죽이면 4대분 처리가 깨짐 / - 재시도 상한(예: 2회)으로 영향을 받는 워커 수를 제한하고 그 요청 1건만 실패 처리 (R3 단점 2) / - 죽은 워커의 제외와 재보충은 3번 시퀀스 경로

> lease 갱신 (C2 보완) / - Lease Renewer는 엔진의 진척 보고가 있는 요청만 갱신 / - 정상적인 긴 생성은 계속 갱신되어 만료되지 않음 / - 엔진이 죽으면 진척 보고가 끊겨 갱신도 멈추므로, 만료는 실제 장애일 때만 발생 (C2 단점 4) / - lease 경계의 중복 결과는 요청 ID로 걸러냄

| # | 보내는 쪽 → 받는 쪽 | EA 메시지 | 구현 |
|---|---|---|---|
| 1 | Credit Manager → Lease Manager | pull: 요청 X 인출 (W1) | `LeaseManager.pull(workerId, versionTag, n)` (HTTP, LeasePort) |
| 2 | Lease Manager → Credit Manager | X + lease (만료 시각 T) | 응답: `List<Lease>` (`expiresAt` = T) |
| 3 | Credit Manager → Engine Scheduler | X 투입 | `EngineScheduler.submit(job)` |
| 4 | Engine Scheduler → Model Runtime | 스텝 실행 (X 포함) | `ModelRuntime.execute(plan)` |
| 5 | Engine Scheduler → Lease Renewer | 진척 보고 | `EngineEventListener.onProgress(requestId)` → LeaseRenewer가 구독 |
| 6 | Lease Renewer → Lease Manager | lease 갱신 (X) | `LeaseRenewer.renewActive()` (주기 작업) → `LeaseManager.renew(leaseId)` (HTTP, LeasePort) |
| 7 | Lease Manager → Lease Renewer | 만료 시각 연장 | 응답: 갱신된 만료 시각 (`Lease.extend(newExpiresAt)`) |
| 8 | Model Runtime → Model Runtime | X 처리 중 엔진 비정상 종료 (W1) | 상황: 엔진 비정상 종료 (mock에서는 장애 주입으로 재현) |
| 9 | Lease Renewer → Lease Renewer | X 진척 보고 끊김, 갱신 중단 | 내부 처리: `renewActive()`가 진척 보고가 끊긴 요청은 갱신하지 않음 |
| 10 | Lease Manager → Lease Manager | 갱신이 끊겨 lease 만료 | `LeaseManager.expireOverdue()` (주기 작업) |
| 11 | Lease Manager → Retry Counter | 만료 보고 (X, 1회차) | `RetryCounter.onExpired(item)` → `QueueItem.increaseRetry()` |
| 12 | Retry Counter → Shared Queue | X 재투입 | `RetryPolicy.shouldRetry(item)`가 true → `SharedQueue.requeue(item)` |
| 13 | Credit Manager → Lease Manager | 다른 워커 W2가 X 인출 | `LeaseManager.pull(...)` (W2의 평소 pull) |
| 14 | Model Runtime → Model Runtime | W2에서도 X로 엔진 종료 | 상황: W2에서도 엔진 종료 |
| 15 | Lease Manager → Retry Counter | 만료 보고 (X, 2회차) | `RetryCounter.onExpired(item)` (2회차) |
| 16 | Retry Counter → Quarantine Queue | X 격리 (더 이상 인출되지 않음) | `RetryPolicy.shouldRetry(item)`가 false → `QuarantineQueue.isolate(item)` |
| 17 | Retry Counter → Front Gateway | X 실패 응답 (에러 1건) | `FailureNotifier.notifyFailure(requestId)` → front-gateway StreamController → `StreamRelay.notifyFailure(requestId)` (HTTP) → SSE → `WebUI.showFailure(requestId)` |

### sd5. 브로커 복제본 승격 (QAS 1)

> Broker Replica (승격된 주 노드) / 승격 후에는 기존 주 브로커와 같은 Admission Controller, Lease Manager 구성으로 동작 / 따라서 pull은 새 주 노드의 Lease Manager, 접수는 새 주 노드의 Admission Controller가 받음 (런타임 연결 그대로)

> QAS 1 에러율 0.1% 이하 / - 동기 복제라 순번을 받은 요청은 주 브로커가 죽어도 잃지 않음 / - 승격 뒤 Broker Replica가 주 브로커 역할(접수, 큐, lease 관리)을 그대로 이어받음 / - 큐와 lease가 그대로라 진행 중 작업도 이어짐 / - 승격은 관리형 기능의 쿼럼 기반 승격을 쓰고, 옛 주 브로커는 차단(fencing)해 주 브로커가 둘이 되지 않음

> C2 단점 5 (감수한 대가) / - 승격하는 동안(수 초) 인출과 접수가 잠깐 멈춤 / - 동기 복제만큼 접수 지연이 늘어남 / - 승격 후 새 대기 복제본을 다시 준비

| # | 보내는 쪽 → 받는 쪽 | EA 메시지 | 구현 |
|---|---|---|---|
| 1 | Front Gateway → Admission Controller | enqueue(X) | `QueueBrokerClient.enqueue(request)` → `AdmissionController.enqueue(request)` (HTTP, AdmissionPort) |
| 2 | Admission Controller → Shared Queue | 적재 | `SharedQueue.push(item)` |
| 3 | Shared Queue → Replicator | 큐 변경 전달 | `Replicator.replicate(change: ChangeRecord)` |
| 4 | Replicator → Broker Replica | 동기 복제 | `BrokerReplica.apply(change)` (HTTP, ReplicaPort → 복제본 broker) |
| 5 | Broker Replica → Replicator | 복제 확인 | 응답: `apply()` 성공 → `replicate()`가 true 반환 |
| 6 | Replicator → Shared Queue | 커밋 | 내부 처리: `push()`가 `replicate()` true를 받은 뒤 커밋 (별도 오퍼레이션 없음) |
| 7 | Admission Controller → Front Gateway | 순번 n (복제 확인 후 응답) | 응답: `enqueue()`의 반환값(순번 n) |
| 8 | Broker Replica → Broker Replica | 주 브로커 응답 끊김 감지 | `BrokerReplica.detectPrimaryLoss()` (주기 작업) |
| 9 | Broker Replica → Broker Replica | 새 주 브로커로 승격 (큐, lease 그대로) | `BrokerReplica.promote()` → `fenceOldPrimary()` (쿼럼 규칙은 5절 참고) |
| 10 | Credit Manager → Broker Replica | 인출 재시도: 새 주 브로커에서 pull | `LeaseManager.pull(...)` — 승격된 노드의 LeaseManager (LeasePort 구현이 주소 전환) |
| 11 | Broker Replica → Credit Manager | 작업 + lease (기존 lease 유지) | 응답: `List<Lease>` (기존 lease 유지) |
| 12 | Front Gateway → Broker Replica | 접수 재연결 | `AdmissionController.enqueue(request)` — 승격된 노드 (AdmissionPort 구현이 주소 전환) |

### sd6. 모델 교체와 이상 시 롤백 (QAS 5, 4)

> QAS 5 모델 교체 용이성 / - 설정 파일 1곳만 수정 / - 진행 중 답변은 기존 모델로 완료, 전환된 새 요청은 새 모델 / - 10분 이내 반영, 끊긴 답변 0건 / - 반영 시작: 첫 v2 워커가 준비되면 새 요청이 v2로 전환되기 시작

> R3 여분 + 비율 전환: 한 대씩 드레인해도 인출 중인 워커는 4대 이상 / 새 요청은 v2 준비 워커 비율만큼만 v2로 보내 4대분 유지 / 버전 태그로 v1 요청은 v1 워커가 끝까지 처리 / U1: 품질 게이트를 통과한 단계만 교체, 운영 중 품질 이상이면 U0 단위로 롤백 (QAS 4 보조 지표)

| # | 보내는 쪽 → 받는 쪽 | EA 메시지 | 구현 |
|---|---|---|---|
| 1 | 운영자 → Web UI | 모델 교체 요청 | `AdminConsole.requestModelSwap(model, stage)` (화면 입력) |
| 2 | Web UI → Model Manager | 새 모델, 사다리 단계 지정 (설정 1곳) | `ModelSwapController.requestSwap(model, stage)` (HTTP) |
| 3 | Model Manager → Model Manager | 게이트 통과 확인 | `QualityGate.evaluate(stage)` → `GateResult.passed` |
| 4 | Model Manager → Model Manager | 목표 단계 기록 | `ModelConfig.setTarget(version)` (model-config의 targetStage 기록) |
| 5 | Worker Agent → Model Manager | 설정 변경 감지 (여분 W5) | `WorkerAgent.onConfigChanged()` ← 주기적으로 `ModelConfig.currentStage()` 조회 (HTTP, ModelConfigPort) |
| 6 | Worker Agent → Credit Manager | 인출 중지, 드레인 (진행 중 답변 완료까지) | `CreditManager.stopPull()` (진행 중 답변은 계속 생성) |
| 7 | Worker Agent → Model Manager | 드레인 시작 | `ModelConfig.onDrainStart(workerId)` (HTTP, ModelConfigPort) |
| 8 | Worker Agent → Model Runtime | v2 재적재 지시 | `WorkerAgent.reload(stage)` → `ModelRuntime.loadWeights(stage)` |
| 9 | Model Runtime → Model Manager | v2 가중치 적재 | model-manager `ModelConfigController` → `ModelStorePort.loadWeights(stage)` (real: `ModelStoreDAO`, mock: `MockModelStoreDAO`) (HTTP, WeightStorePort) |
| 10 | Model Runtime → Worker Agent | 적재, 생성 검사 완료 | 응답: `ModelRuntime.generationTest()` / `WorkerAgent.selfTest()` 결과 |
| 11 | Worker Agent → Model Manager | W5 v2 준비 완료 | `ModelConfig.onReady(workerId)` (HTTP, ModelConfigPort) |
| 12 | Model Manager → Model Manager | v2 비율 갱신 | 내부 처리: `VersionRatio.ratio()` 갱신 (readyWorkers / pullingWorkers) |
| 13 | Front Gateway → Model Manager | 버전 비율 조회 | `ModelConfigClient.getRatio()` → `ModelConfig.getRatio()` (HTTP, ModelConfigPort) |
| 14 | Front Gateway → Front Gateway | 비율에 따라 새 요청에 v1/v2 태그 | 내부 처리: `VersionTagger.tag(request)` |
| 15 | Credit Manager → Credit Manager | W5는 v2 태그 작업만 인출 | `CreditManager.setVersion(versionTag)` — v2 작업만 pull하고 인출을 다시 시작 |
| 16 | Worker Agent → Credit Manager | 인출 중지, 드레인 | `CreditManager.stopPull()` |
| 17 | Worker Agent → Model Manager | 드레인 시작 | `ModelConfig.onDrainStart(workerId)` |
| 18 | Worker Agent → Model Runtime | v2 재적재 지시 | `WorkerAgent.reload(stage)` → `ModelRuntime.loadWeights(stage)` |
| 19 | Model Runtime → Model Manager | v2 가중치 재적재 | model-manager `ModelConfigController` → `ModelStorePort.loadWeights(stage)` (real: `ModelStoreDAO`, mock: `MockModelStoreDAO`) (HTTP, WeightStorePort) |
| 20 | Worker Agent → Model Manager | v2 준비 완료 | `ModelConfig.onReady(workerId)` |
| 21 | Credit Manager → Credit Manager | v2 태그 작업만 인출 | `CreditManager.setVersion(versionTag)` |
| 22 | Model Manager → Model Manager | v2 비율 100% | 내부 처리: `VersionRatio.ratio()` = 1.0, `ModelConfig.switchTo(stage)`로 activeStage 확정 |
| 23 | Web UI → Model Manager | 상태 조회 | `AdminConsole.showSwapStatus()` → `ModelSwapController.getSwapStatus()` (HTTP 조회) |
| 24 | Model Manager → Web UI | 교체 완료 (10분 이내, 끊긴 답변 0건) | 응답: `getSwapStatus()`의 반환값 |
| 25 | Web UI → 운영자 | 완료 표시 | 화면 표시: AdminConsole |
| 26 | Model Manager → Model Manager | 품질 이상 감지 | `SampleForwarder.forward(sample)` → `QualityGate.observe(sample)` (HTTP, QualitySamplePort)에서 이상 감지 |
| 27 | Model Manager → Model Manager | U0로 되돌림 | `RollbackManager.rollbackToU0()` → `ModelConfig.switchTo(U0Stage)` |
| 28 | Worker Agent → Model Manager | 설정 변경 감지 | `WorkerAgent.onConfigChanged()` ← `ModelConfig.currentStage()` 조회 |
| 29 | Worker Agent → Model Runtime | U0 재적재 지시 | `WorkerAgent.reload(stage)` → `ModelRuntime.loadWeights(stage)` (교체와 같은 롤링 절차: 6~11번 반복) |
| 30 | Model Runtime → Model Manager | U0 가중치 재적재 (같은 롤링 절차) | model-manager `ModelConfigController` → `ModelStorePort.loadWeights(stage)` (real: `ModelStoreDAO`, mock: `MockModelStoreDAO`) (HTTP, WeightStorePort) |
| 31 | Web UI → Model Manager | 상태 조회 | `AdminConsole.showSwapStatus()` → `ModelSwapController.getSwapStatus()` |
| 32 | Model Manager → Web UI | 롤백 알림 | 응답: `getSwapStatus()`의 반환값 (롤백 상태) |
| 33 | Web UI → 운영자 | 롤백 표시 | 화면 표시: AdminConsole |

## 5. EA에 없어서 코드에서 보충하는 것

아래는 EA 다이어그램에 클래스·오퍼레이션으로 그려져 있지 않지만, 구현에 필요해서 정한 것이다. 새 업무 클래스를 만들지 않고 EA에 있는 클래스 안에 넣는다.

**EA를 고칠지, 이 절에 기록할지**
- 구현하다 EA와 다른 점이 생기면 아키텍처에 영향이 있는지로 나눈다.
  - EA를 고친다: 컴포넌트 사이에 새 호출이 생길 때, EA 클래스에 새 책임이나 새 오퍼레이션이 생길 때, 전략(C2·T3·U1·R3)의 동작이 바뀔 때. EA를 먼저 고치고 이 문서의 2절과 머리말 수정 기록을 맞춘다.
  - EA는 두고 이 절에 기록한다: 이미 있는 호출을 HTTP로 구현하는 방법(얇은 입구, DTO), EA에 이미 있는 오퍼레이션·속성을 읽어 모으는 조회, 시연·관찰용으로 더 보내는 값.
- 사례
  - EA를 고친 것: sd6 마지막 워커 드레인 판단용 오퍼레이션 `SharedQueue.countMatching`, `LeaseManager.remainingFor`, `CreditManager.remainingForVersion` (4차 수정)
  - 이 절에 기록한 것: common DTO, 얇은 REST 입구, 지표 조회 항목
- 지표 조회 항목: 런타임 화살표 라벨은 대표 항목만 적는다("큐 길이 수집", "세션 · GPU · TTFT 수집"). 코드의 수집 응답은 운영자 화면에서 4대분 상한, 격리, 교체 진행을 보이려고 EA에 이미 있는 값을 더 담는다. 새 호출이나 오퍼레이션이 아니라서 EA는 그대로 둔다.
  - Queue Broker: 큐 길이(`SharedQueue.length`)와 함께 격리 수(`QuarantineQueue.list`), 실행 중 lease 수와 상한(`LeaseLimiter.activeLeases`, `concurrencyCap`)
  - Worker: 세션·GPU·TTFT(`EngineScheduler`)와 함께 버전 태그(`WorkerAgent`)

**브로커 쿼럼 승격 (sd5, `CLAUDE.md` 6절 규칙 1~5)**
- 주 브로커 쪽 규칙(1~3: 복제 확인 후 커밋, 복제본이 끊기면 락을 쥔 동안만 혼자 커밋, 둘 다 안 되면 쓰기 정지)은 `Replicator` 안에 넣는다.
- 복제본 쪽 규칙(4: X초 동안 연락이 없고 락을 얻으면 승격, epoch + 1)은 `BrokerReplica.detectPrimaryLoss()`와 `promote()` 안에 넣는다.
- Redis의 `broker-primary` 락 접근은 기술 보조 클래스(`config`/`adapter`)로 만들고, Replicator와 BrokerReplica만 사용한다.
- epoch: EA `ChangeRecord.epoch`(3차 수정에서 추가)와 lease 응답 DTO의 `epoch: long`으로 전달한다.

**인출 재개**
- EA에는 `CreditManager.stopPull()`만 있고 다시 시작하는 오퍼레이션이 없다. sd6처럼 `setVersion(versionTag)`이 호출되면 인출을 다시 시작한다.

**sd6 마지막 워커의 드레인 (옛 버전 작업 남기지 않기)**
- EA sd6은 워커가 한 대씩 드레인·재적재하는 흐름만 그린다. 그런데 태그는 접수 시점 비율로 붙고 인출은 버전 일치 작업만 하므로, 현재 버전을 인출하는 마지막 워커가 멈추면 큐에 남은 옛 버전 작업을 가져갈 워커가 없어진다. 롤백도 같다.
- 그래서 `ModelConfig.onDrainStart(workerId)`는 드레인 허가와 함께 "현재 버전을 인출하는 마지막 워커인지"(`lastOfVersion`)를 알려 준다(`DrainStartResponse`). 마지막이 아닌 워커는 허가 즉시 인출을 멈춘다.
- 마지막 워커는 인출을 계속하다가, 큐에 남은 같은 버전 작업(`CreditManager.remainingForVersion()` → `LeaseManager.remainingFor()` → `SharedQueue.countMatching()`)과 진행 중 답변이 모두 0인 상태가 `last-drain-quiet-ms` 동안 이어질 때만 멈춘다(`WorkerAgent` 내부 판단).
- "빈 pull이 이어지면 멈춘다"로 판단하지 않는다. 피크에는 슬롯이 차 크레딧이 0이거나 4대분 상한에 걸려 빈 pull이 이어져도 옛 버전 작업이 남아 있을 수 있다.
- 남은 수는 새 API 없이 기존 pull 응답(`PullResponse.remainingForVersion`)에 싣고, 크레딧이 0일 때는 n = 0 pull로 확인한다(상한을 쓰지 않음). 런타임 뷰에 없는 호출은 만들지 않는다.
- 조용한 시간(`last-drain-quiet-ms`)은 Front Gateway가 비율을 다시 읽기 전에 옛 태그로 막 들어온 요청을 받기 위한 여유다.
- 한계: 마지막 워커가 멈춘 뒤 그 버전 작업이 엔진 크래시로 lease 만료되어 다시 큐에 들어오면 가져갈 워커가 없다(드레인 중 엔진 장애라는 이중 장애).

**복제 커밋**
- sd5 6번 "커밋"은 오퍼레이션이 없다. `SharedQueue.push()`와 `LeaseManager`의 상태 변경이 `Replicator.replicate()`의 결과를 받은 뒤 반영하는 방식으로 만든다.

**lease 회수와 재시도 횟수**
- 서버 장애 회수(sd3, `LeaseManager.reclaim()`)는 작업을 바로 공용 큐로 되돌리고 재시도 횟수를 올리지 않는다.
- lease 만료(sd4, `expireOverdue()`)만 `RetryCounter`를 거쳐 재시도 횟수를 센다.

**외부 의존의 mock / real (`CLAUDE.md` 2절 표)**
- ModelRuntime은 EA대로 두고, 엔진 호출만 `InferenceEnginePort`로 뺀다. ModelRuntime의 `execute()`, `loadWeights()`, `generationTest()`가 이 Port에 위임하고, 토큰 전달(`StreamPublisher.publish()`)은 ModelRuntime이 한다.
- Replenisher는 `SpotInstancePort`를 쓴다. real은 EA의 SpotInstanceClient, mock은 fake-aws를 부르는 MockSpotInstanceClient다.
- 가중치 정보는 `ModelStorePort`로 읽는다. real은 EA의 ModelStoreDAO(S3), mock은 MockModelStoreDAO다.

**추론 엔진 계약 (`InferenceEnginePort`, mock과 real 공통)**
- 오퍼레이션: `StepResult execute(StepPlan plan)`, `void loadWeights(LadderStage stage)`, `boolean generationTest()`. ModelRuntime의 같은 이름 오퍼레이션이 그대로 위임한다.
- `execute(plan)`은 EngineScheduler가 매 스텝 부른다. 반환하는 `StepResult`에는 이번 호출에서 나온 토큰(`chunks`), Prefill을 끝낸 요청(`prefillDoneIds`), 생성이 끝난 요청(`finishedIds`)이 담긴다. 위쪽 클래스(EngineScheduler, CreditManager, LeaseRenewer, StreamPublisher)는 이 결과만 보고 동작하므로 mock과 real에서 코드가 같다.
- mock (`MockInferenceEngine`): StepPlan을 그대로 따른다. 스텝 시간(`CLAUDE.md` 가짜 엔진 공식)만큼 지연한 뒤 결과를 돌려준다. 생성 길이는 설정값(요청별 출력 토큰 수 범위)으로 정한다.
- real (`VllmEngineAdapter`): `plan.prompts`에 처음 나온 요청은 그 본문으로 vLLM(OpenAI 호환 `/v1/completions`, `stream: true`)에 요청을 연다. 이후 `execute()`가 불릴 때마다 지난 호출 이후 받은 토큰을 같은 `StepResult` 모양으로 돌려주고, 첫 토큰이 온 요청을 `prefillDoneIds`, 스트림이 끝난 요청을 `finishedIds`에 넣는다. 스텝 구성은 vLLM의 chunked prefill이 하고, B는 vLLM 시작 설정 `max_num_batched_tokens`로 넣는다.
- 실패: 엔진이 응답하지 않거나 연결이 끊기면 두 구현 모두 예외를 던진다. 진척 보고가 끊기므로 sd4의 lease 만료, 재시도, 격리 경로가 mock과 real에서 같게 일어난다.
- 엔진 주소는 OpenAI 호환 API면 어디든 된다(자체 vLLM, 호환 API를 주는 클라우드 서비스). 다만 호스팅 API에서는 `loadWeights`(사다리 단계), KV 용량을 제어할 수 없으므로 생성 확인 용도로만 쓴다.

**common 모듈의 DTO와 API 경로 (`CLAUDE.md` 2절, 3절)**
- EA의 Common 패키지에는 컴포넌트 사이를 오가는 도메인 데이터(entity) 4개만 있다: Request, StreamChunk, WorkerStatus, QualitySample.
- 코드의 `common`에는 이 4개와 함께, 앱끼리 HTTP(JSON)로 주고받기 위한 기술 보조 클래스를 둔다. 업무 규칙이 없어서 EA에는 그리지 않는다.
  - 요청/응답 DTO: `XxxRequest`, `XxxResponse` (예: `PullRequest`, `PullResponse`, `LeaseResponse`). Java `record`로 만든다.
  - API 경로 상수: `ApiPaths`
  - 에러 응답: `ErrorResponse`, `ErrorCode`
- DTO를 앱마다 따로 두지 않고 `common` 한 곳에 모은다. 한 저장소에서 함께 빌드·배포하므로, 보내는 쪽과 받는 쪽이 같은 규격을 쓰고 필드가 어긋나면 컴파일에서 드러나게 하는 것이 앱별 독립 수정보다 중요하다고 판단했다.
- 대가: DTO 하나를 바꾸면 그 DTO를 쓰는 모든 앱이 함께 바뀐다. 그래서 `common`에는 로직을 두지 않고, DTO나 API 경로를 바꾸기 전에 팀에 알린다(`CLAUDE.md` 7절). JSON 왕복은 `DtoJsonContractTest`로 확인한다.

**얇은 REST 입구와 앱 시작점 (`CLAUDE.md` 3절 Port 목록, 4절)**
- EA는 런타임 뷰의 화살표가 닿는 곳마다 그 메시지를 받는 클래스를 둔다. 런타임 부품은 같은 이름의 클래스가 받고(예: Worker → Lease Manager의 pull은 `LeaseManager`, Front Gateway → Admission Controller의 enqueue는 `AdmissionController`), 차일드 다이어그램이 없는 컴포넌트는 Module View에서 책임별로 나눈 클래스가 받는다(예: Web UI → Front Gateway의 질문은 `QuestionController`, Web UI → Model Manager의 교체 요청은 `ModelSwapController`, Web UI → Monitoring은 `AlertService`·`MetricsCollector`).
- 코드에서는 앱이 달라 HTTP로 부르므로, 받는 클래스의 역할에 따라 HTTP를 받는 곳을 정한다.
  - 받는 클래스가 입구 역할이면(EA 이름이 `~Controller`: `AdmissionController`, `QuestionController`, `ModelSwapController`) 그 클래스가 HTTP를 직접 받는다.
  - 받는 클래스가 업무 로직이면(`LeaseManager`, `HealthDetector`, `NoticeHandler`, `AlertService` 등) 로직에 HTTP를 섞지 않도록 앞에 얇은 입구 `XxxController`를 둔다. 업무 판단이 없는 HTTP 연결부라 EA에는 그리지 않는다.
  - 얇은 입구가 하는 일은 입력값 확인, 요청·응답 DTO 변환, 대상 클래스 호출뿐이다. 조회용 입구는 여러 클래스의 값을 읽어 한 응답으로 모으기도 한다. 무엇을 허용·거절·선택할지 같은 업무 판단은 하지 않고, 전부 EA의 control 클래스에 있다.
  - 그래서 HTTP 매핑(`@PostMapping` 등)은 이름이 `Controller`로 끝나는 클래스에만 있고, 업무 로직 클래스에는 없다. 예외: `BudgetController`는 런타임 부품 이름을 그대로 쓴 로직 클래스이며 HTTP를 받지 않는다(`CLAUDE.md` 4절).
- 코드에서 추가한 얇은 입구와 호출 대상:
  - 대상 하나에 넘김
    - front-gateway: `StreamController` → StreamRelay (토큰 수신, 실패 통지)
    - broker: `LeaseController` → LeaseManager, `ReplicationController` → BrokerReplica
    - membership: `HeartbeatController` → HealthDetector, `NoticeController` → NoticeHandler(회수 통지), Replenisher(기동 완료)
    - monitoring: `AlertController` → AlertService, `MetricsController` → MetricsCollector, `QualitySampleController` → SampleForwarder
  - 여러 클래스의 값을 모아 응답 (조회 전용)
    - broker: `QueueMetricsController` → SharedQueue(큐 길이), QuarantineQueue(격리 수), LeaseLimiter(실행 중 수, 상한)
    - worker: `WorkerController`의 지표 조회 → EngineScheduler, WorkerAgent. 인출 중지·기동 신호는 WorkerAgent 하나에 넘긴다
  - 두 클래스를 차례로 호출
    - model-manager: `ModelConfigController`의 가중치 적재 → `ModelConfig.stageOf()`로 단계 이름을 단계 객체로 바꾸고 `ModelStorePort.loadWeights()`로 읽은 뒤 크기를 GB로 바꿔 응답. 나머지 주소는 ModelConfig 또는 QualityGate 하나에 넘긴다
- 얇은 입구는 대상 클래스와 같은 패키지에 두고(대상이 여럿이면 Port 목록의 첫 대상 패키지), 업무 규칙을 넣지 않는다. boundary 클래스를 직접 참조하지 않는 것은 ArchUnit으로 검사한다(`CLAUDE.md` 8절).
- 앱마다 Spring Boot 시작점 `XxxApplication`(예: `BrokerApplication`)이 모듈 기본 패키지에 하나씩 있다. 기술적으로 필요한 클래스라 EA에 그리지 않는다.
- fake-aws의 `FakeAwsController`는 외부 액터 AWS(스팟)를 흉내 내는 시연 앱의 입구라 EA 밖이다(`CLAUDE.md` 2절 "가짜 AWS").

**질문 접수와 스트림**
- 질문은 긴 입력을 담으므로 `POST /api/front-gateway/questions` 한 번으로 보내고, 같은 응답을 SSE 스트림으로 유지한다. 순번 안내, 토큰, 실패 통지가 모두 이 스트림으로 간다.
- 대기 중 순번 갱신은 Front Gateway가 주기적으로 `QueueBrokerClient.getPosition(requestId)`를 조회해 `StreamRelay.notifyPosition()`으로 보낸다.

**새 워커의 ID**
- worker-spare는 자기 `INSTANCE_ID`로 합류한다. `Replenisher.onInstanceReady(instanceId)`의 instanceId와 이후 하트비트의 workerId가 같다.
