package com.llmserving.common;

/**
 * 모든 API 경로 상수 (CLAUDE.md 5절 "API 경로").
 * 형식: /api/{모듈명}/{자원}[/{동작}]. 경로 변수는 {이름}으로 쓴다.
 */
public final class ApiPaths {

    /** 앱 사이 호출에 요청 ID를 넘기는 헤더 */
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    // front-gateway
    public static final String FRONT_GATEWAY = "/api/front-gateway";
    /** POST 질문 접수. 응답은 SSE 스트림 */
    public static final String QUESTIONS = FRONT_GATEWAY + "/questions";
    /** POST 토큰 푸시 (Worker StreamPublisher → StreamRelay) */
    public static final String STREAM_CHUNKS = FRONT_GATEWAY + "/streams/{requestId}/chunks";
    /** POST 실패 통지 (Broker FailureNotifier → StreamRelay) */
    public static final String FAILURES = FRONT_GATEWAY + "/failures/{requestId}";

    // broker
    public static final String BROKER = "/api/broker";
    /** POST enqueue */
    public static final String REQUESTS = BROKER + "/requests";
    /** GET 순번 조회 */
    public static final String REQUEST_POSITION = BROKER + "/requests/{requestId}/position";
    /** POST 크레딧 pull */
    public static final String LEASES_PULL = BROKER + "/leases/pull";
    /** POST lease 갱신 */
    public static final String LEASE_RENEW = BROKER + "/leases/{leaseId}/renew";
    /** POST 완료 ack */
    public static final String LEASE_COMPLETE = BROKER + "/leases/{leaseId}/complete";
    /** POST 워커 lease 회수 (Membership LeaseReclaimer) */
    public static final String WORKER_RECLAIM = BROKER + "/workers/{workerId}/reclaim";
    /** POST 동기 복제 (주 broker → 복제본 broker) */
    public static final String REPLICATION_CHANGES = BROKER + "/replication/changes";
    /** GET 큐 길이 (Monitoring) */
    public static final String QUEUE_METRICS = BROKER + "/metrics/queue";

    // worker
    public static final String WORKER = "/api/worker";
    /** POST 인출 중지 (Membership NoticeHandler → WorkerAgent). 멱등 */
    public static final String STOP_PULL = WORKER + "/stop-pull";
    /** POST 기동 신호 (fake-aws → worker-spare) */
    public static final String BOOT = WORKER + "/boot";
    /** GET 세션, GPU, TTFT (Monitoring) */
    public static final String WORKER_METRICS = WORKER + "/metrics";

    // membership
    public static final String MEMBERSHIP = "/api/membership";
    /** POST 하트비트, 상태 보고 (Worker WorkerAgent → HealthDetector) */
    public static final String HEARTBEATS = MEMBERSHIP + "/heartbeats";
    /** POST 스팟 회수 통지 (AWS → NoticeHandler) */
    public static final String NOTICES = MEMBERSHIP + "/notices";
    /** POST 인스턴스 기동 완료 (AWS → Replenisher) */
    public static final String INSTANCE_READY = MEMBERSHIP + "/instances/{instanceId}/ready";

    // model-manager
    public static final String MODEL_MANAGER = "/api/model-manager";
    /** POST 모델 교체 요청 */
    public static final String SWAPS = MODEL_MANAGER + "/swaps";
    /** GET 교체·롤백 상태 */
    public static final String SWAP_STATUS = MODEL_MANAGER + "/swaps/status";
    /** GET 모델 설정 (Worker 설정 감지) */
    public static final String MODEL_CONFIG = MODEL_MANAGER + "/config";
    /** GET 버전 비율 (Front Gateway) */
    public static final String MODEL_CONFIG_RATIO = MODEL_MANAGER + "/config/ratio";
    /** POST 드레인 시작 보고 (Worker) */
    public static final String WORKER_DRAIN = MODEL_MANAGER + "/workers/{workerId}/drain";
    /** POST 준비 완료 보고 (Worker) */
    public static final String WORKER_READY = MODEL_MANAGER + "/workers/{workerId}/ready";
    /** GET 단계별 가중치 정보 (Worker ModelRuntime) */
    public static final String WEIGHTS = MODEL_MANAGER + "/weights/{stage}";
    /** POST 응답 품질 샘플 (Monitoring SampleForwarder → QualityGate) */
    public static final String QUALITY_SAMPLES = MODEL_MANAGER + "/quality-samples";

    // monitoring
    public static final String MONITORING = "/api/monitoring";
    /** GET 알림 조회 (AdminConsole), POST 알림 등록 (Membership RecoveryNotifier) */
    public static final String ALERTS = MONITORING + "/alerts";
    /** GET 지표 조회 (AdminConsole). 쿼리 파라미터 name */
    public static final String METRICS = MONITORING + "/metrics";

    // fake-aws
    public static final String FAKE_AWS = "/api/fake-aws";
    /** POST 대체 인스턴스 요청 (Membership Replenisher) */
    public static final String INSTANCES = FAKE_AWS + "/instances";
    /** POST 시연 명령: 스팟 회수 통지 발송 */
    public static final String SPOT_RECLAIMS = FAKE_AWS + "/spot-reclaims";

    private ApiPaths() {
    }
}
