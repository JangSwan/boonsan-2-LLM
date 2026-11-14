package com.llmserving.common;

/**
 * 앱 사이 API 경로 상수 (CLAUDE.md 5절 "API 경로").
 * 형식: /api/{모듈명}/{자원}[/{동작}]. 경로 변수는 {이름} 형식 그대로 두어
 * 받는 쪽 @XxxMapping과 보내는 쪽 RestClient uri(template, vars)에서 같이 쓴다.
 */
public final class ApiPaths {

    private ApiPaths() {
    }

    /** 앱 사이 호출에 요청 ID를 넘기는 헤더 (CLAUDE.md 5절 "로그"). */
    public static final String HEADER_REQUEST_ID = "X-Request-Id";

    /** 헬스 체크 (compose healthcheck). */
    public static final String HEALTH = "/actuator/health";

    /** front-gateway: 질문 접수, 토큰 수신, 실패 통지. */
    public static final class FrontGateway {
        private FrontGateway() {
        }

        public static final String BASE = "/api/front-gateway";
        /** 질문 접수. 응답은 SSE 스트림 (QuestionController.receive). */
        public static final String QUESTIONS = BASE + "/questions";
        /** 워커가 토큰 조각을 푸시 (StreamPort → StreamRelay.onChunk). */
        public static final String STREAM_CHUNKS = BASE + "/streams/{requestId}/chunks";
        /** broker가 격리된 요청의 실패를 통지 (FailureNotifier → StreamRelay.notifyFailure). */
        public static final String FAILURES = BASE + "/failures/{requestId}";
    }

    /** broker: 접수, lease, 회수, 큐 지표, 복제. */
    public static final class Broker {
        private Broker() {
        }

        public static final String BASE = "/api/broker";
        /** enqueue (AdmissionController.enqueue). */
        public static final String REQUESTS = BASE + "/requests";
        /** 순번 조회 (AdmissionController.getPosition). */
        public static final String REQUEST_POSITION = BASE + "/requests/{requestId}/position";
        /** 크레딧 pull (LeaseManager.pull). */
        public static final String LEASES_PULL = BASE + "/leases/pull";
        /** lease 갱신 (LeaseManager.renew). */
        public static final String LEASE_RENEW = BASE + "/leases/{leaseId}/renew";
        /** 완료 ack (LeaseManager.complete). */
        public static final String LEASE_COMPLETE = BASE + "/leases/{leaseId}/complete";
        /** 워커 lease 회수 (LeaseManager.reclaim). */
        public static final String WORKER_RECLAIM = BASE + "/workers/{workerId}/reclaim";
        /** 큐 길이 수집 (QueueMetricsController → SharedQueue). */
        public static final String QUEUE_METRICS = BASE + "/queue/metrics";
        /** 주 브로커 → 복제본 동기 복제 (ReplicaPort → BrokerReplica.apply). */
        public static final String REPLICATIONS = BASE + "/replications";
    }

    /** worker: 인출 중지, 기동 신호, 지표. */
    public static final class Worker {
        private Worker() {
        }

        public static final String BASE = "/api/worker";
        /** 인출 중지 (WorkerController → WorkerAgent.stopPull). 멱등. */
        public static final String STOP_PULL = BASE + "/stop-pull";
        /** 예비 워커 기동 신호 (fake-aws가 인스턴스 생성을 흉내 냄). */
        public static final String BOOT = BASE + "/boot";
        /** 세션 · GPU · TTFT 수집 (WorkerController → EngineScheduler). */
        public static final String METRICS = BASE + "/metrics";
    }

    /** membership: 하트비트, 회수 통지, 기동 완료. */
    public static final class Membership {
        private Membership() {
        }

        public static final String BASE = "/api/membership";
        /** 하트비트 · 상태 보고 (HeartbeatController → HealthDetector.onHeartbeat). */
        public static final String HEARTBEATS = BASE + "/heartbeats";
        /** 스팟 회수 통지 (NoticeController → NoticeHandler.onReclaimNotice). */
        public static final String NOTICES = BASE + "/notices";
        /** 인스턴스 기동 완료 (NoticeController → Replenisher.onInstanceReady). */
        public static final String INSTANCES_READY = BASE + "/instances/ready";
    }

    /** model-manager: 교체, 비율, 설정 감지, 드레인·준비 보고, 가중치, 품질 샘플. */
    public static final class ModelManager {
        private ModelManager() {
        }

        public static final String BASE = "/api/model-manager";
        /** 교체 요청 (ModelSwapController.requestSwap). */
        public static final String SWAPS = BASE + "/swaps";
        /** 교체 상태 조회 (ModelSwapController.getSwapStatus). */
        public static final String SWAP_STATUS = BASE + "/swaps/status";
        /** v2 비율 조회 (ModelConfig.getRatio). */
        public static final String RATIO = BASE + "/ratio";
        /** 워커의 설정 변경 감지 (ModelConfig.currentStage 등). 쿼리 파라미터 workerId, versionTag. */
        public static final String CONFIG = BASE + "/config";
        /** 드레인 시작 보고 (ModelConfig.onDrainStart). */
        public static final String WORKER_DRAIN_START = BASE + "/workers/{workerId}/drain-start";
        /** 준비 완료 보고 (ModelConfig.onReady). */
        public static final String WORKER_READY = BASE + "/workers/{workerId}/ready";
        /** 가중치 적재 (WeightStorePort → ModelStorePort.loadWeights). */
        public static final String WEIGHTS_LOAD = BASE + "/weights/load";
        /** 운영 중 품질 샘플 (QualitySamplePort → QualityGate.observe). */
        public static final String QUALITY_SAMPLES = BASE + "/quality-samples";
    }

    /** monitoring: 알림, 지표 조회. */
    public static final class Monitoring {
        private Monitoring() {
        }

        public static final String BASE = "/api/monitoring";
        /** POST: 알림 발생 (AlertService.raise), GET: 알림 조회 (AlertService.getAlerts). */
        public static final String ALERTS = BASE + "/alerts";
        /** 지표 조회 (MetricsCollector.query). 쿼리 파라미터 name. */
        public static final String METRICS = BASE + "/metrics";
        /** 응답 품질 샘플 입력 (SampleForwarder.forward). 시연에서 품질 이상을 주입한다. */
        public static final String QUALITY_SAMPLES = BASE + "/quality-samples";
    }

    /** fake-aws: 인스턴스 재생성, 시연용 스팟 회수 명령. */
    public static final class FakeAws {
        private FakeAws() {
        }

        public static final String BASE = "/api/fake-aws";
        /** 대체 인스턴스 요청 (SpotInstancePort.requestInstance). */
        public static final String INSTANCES = BASE + "/instances";
        /** 시연 명령: 대상 워커에 대한 스팟 회수 통지 발송. */
        public static final String SPOT_RECLAIMS = BASE + "/spot-reclaims";
    }

    /** 질문 SSE 스트림의 이벤트 이름. */
    public static final class SseEvents {
        private SseEvents() {
        }

        /** 대기 순번 안내. 데이터: PositionResponse. */
        public static final String POSITION = "position";
        /** 토큰 조각. 데이터: StreamChunk. */
        public static final String TOKEN = "token";
        /** 실패 응답. 데이터: ErrorResponse. */
        public static final String FAILURE = "failure";
    }
}
