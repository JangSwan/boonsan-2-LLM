import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * QAS1(폭주)·QAS2(첫 응답 속도) 부하 생성기. Java 21 표준 라이브러리만 쓰는 단일 파일이다.
 *
 * <pre>
 *   # QAS1: N건을 한꺼번에 보낸다
 *   java scripts/loadtest/LoadTest.java --mode burst --requests 500
 *   # QAS2: 동시 C건을 계속 유지한다 (워밍업 구간은 통계에서 뺀다)
 *   java scripts/loadtest/LoadTest.java --mode steady --concurrency 80 --duration-sec 300 --warmup-sec 60
 * </pre>
 *
 * 요청은 Front Gateway의 질문 접수(POST /api/front-gateway/questions)로 보내고, 응답 SSE의
 * position(대기 순번), token(StreamChunk, isFirst/isLast), failure 이벤트 시각을 요청마다 기록한다.
 * 판정 기준은 설계 보고서의 QAS 시나리오 6요소를 그대로 따른다.
 */
public class LoadTest {

    // ---- 보고서 기준값 (QAS1·QAS2) ----
    static final long SHORT_FIRST_TOKEN_LIMIT_MS = 30_000;
    static final long LONG_POSITION_LIMIT_MS = 1_000;
    static final long LONG_FIRST_TOKEN_LIMIT_MS = 180_000;
    static final double QAS1_ERROR_RATE_LIMIT = 0.001;
    static final long QAS2_P95_LIMIT_MS = 3_000;

    static final Pattern POSITION = Pattern.compile("\"position\"\\s*:\\s*(\\d+)");
    static final Pattern TOKEN = Pattern.compile("\"token\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    static final Pattern TAG = Pattern.compile("\\[Q(\\d+)\\]");

    enum Kind { SHORT, LONG_CHAT, LONG_DOC;
        boolean isLong() { return this != SHORT; }
        String label() { return switch (this) { case SHORT -> "short"; case LONG_CHAT -> "long-chat"; case LONG_DOC -> "long-doc"; }; }
    }

    /** 요청 하나의 기록. 시각은 보낸 시점 기준 경과 밀리초, 없으면 -1. */
    static final class Result {
        final int index;
        final Kind kind;
        final int inputTokens;
        long sentAtMs;           // 시험 시작부터 보낸 시점까지
        int httpStatus = -1;
        long positionMs = -1;
        int initialPosition = -1;
        long firstTokenMs = -1;
        long doneMs = -1;
        boolean failed;
        String error = "";
        /** --check: 답변 맨 앞에 쓰라고 한 이 요청의 표식과 덧셈 문제의 정답 */
        String tag;
        int expectedSum = -1;
        /** --check 또는 --answers일 때만 받은 토큰을 모은다 */
        StringBuilder answer;

        Result(int index, Kind kind, int inputTokens) {
            this.index = index;
            this.kind = kind;
            this.inputTokens = inputTokens;
        }

        /** QAS1 판정: 요청 단위 에러 여부와 이유. */
        String qas1Error() {
            if (httpStatus != 200) return "http-" + httpStatus;
            if (failed) return "failure";
            if (kind == Kind.SHORT) {
                if (firstTokenMs < 0 || firstTokenMs > SHORT_FIRST_TOKEN_LIMIT_MS) return "short-ttft>30s";
                return null;
            }
            boolean guided = (positionMs >= 0 && positionMs <= LONG_POSITION_LIMIT_MS)
                    || (firstTokenMs >= 0 && firstTokenMs <= LONG_POSITION_LIMIT_MS);
            if (!guided) return "long-position>1s";
            if (firstTokenMs < 0 || firstTokenMs > LONG_FIRST_TOKEN_LIMIT_MS) return "long-ttft>180s";
            return null;
        }
    }

    // ---- 설정 ----
    final String url;
    final String mode;
    final int requests;
    final int concurrency;
    final int durationSec;
    final int warmupSec;
    final long seed;
    final int timeoutSec;
    final int drainSec;
    final String csv;
    final boolean failOnFail;
    final int charsPerToken;
    final int shortTokensMin;
    final int shortTokensMax;
    final int longTokens;
    final boolean check;
    final String answersFile;

    final HttpClient client;
    final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
    final ConcurrentLinkedQueue<Result> results = new ConcurrentLinkedQueue<>();
    final AtomicInteger inFlight = new AtomicInteger();
    final AtomicInteger maxInFlight = new AtomicInteger();
    /** 진행 중인 요청과 그 응답 스트림. 마감(drain)이나 Ctrl+C 때 끊는 데 쓴다. */
    final Map<Result, InputStream[]> active = new java.util.concurrent.ConcurrentHashMap<>();
    final java.util.concurrent.atomic.AtomicBoolean reported = new java.util.concurrent.atomic.AtomicBoolean();
    long startNanos;

    LoadTest(Map<String, String> a) {
        url = a.getOrDefault("url", "http://localhost:8080").replaceAll("/+$", "");
        mode = a.getOrDefault("mode", "burst");
        requests = Integer.parseInt(a.getOrDefault("requests", "1000"));
        concurrency = Integer.parseInt(a.getOrDefault("concurrency", "160"));
        durationSec = Integer.parseInt(a.getOrDefault("duration-sec", "300"));
        warmupSec = Integer.parseInt(a.getOrDefault("warmup-sec", "60"));
        seed = Long.parseLong(a.getOrDefault("seed", "42"));
        timeoutSec = Integer.parseInt(a.getOrDefault("timeout-sec", "600"));
        drainSec = Integer.parseInt(a.getOrDefault("drain-sec", "60"));
        csv = a.get("csv");
        failOnFail = a.containsKey("fail-on-fail");
        charsPerToken = Integer.parseInt(a.getOrDefault("chars-per-token", "4"));
        shortTokensMin = Integer.parseInt(a.getOrDefault("short-tokens-min", "20"));
        shortTokensMax = Integer.parseInt(a.getOrDefault("short-tokens-max", "500"));
        longTokens = Integer.parseInt(a.getOrDefault("long-tokens", "4000"));
        check = a.containsKey("check");
        answersFile = a.get("answers");
        client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
    }

    public static void main(String[] args) throws Exception {
        // 콘솔 인코딩과 상관없이 한국어 요약이 깨지지 않게 UTF-8로 출력한다
        System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true,
                StandardCharsets.UTF_8));
        Map<String, String> a = parseArgs(args);
        if (a.containsKey("help")) {
            usage();
            return;
        }
        LoadTest t = new LoadTest(a);
        // Ctrl+C로 멈춰도 그때까지의 결과를 요약한다. 끝나지 않은 요청은 interrupted로 센다
        Runtime.getRuntime().addShutdownHook(new Thread(t::reportOnInterrupt));
        boolean pass = switch (t.mode) {
            case "burst" -> t.runBurst();
            case "steady" -> t.runSteady();
            default -> throw new IllegalArgumentException("--mode는 burst 또는 steady: " + t.mode);
        };
        t.watchdog.shutdownNow();
        if (t.failOnFail && !pass) {
            System.exit(1);
        }
        System.exit(0);
    }

    // ---- 실행 ----

    /** QAS1: 모든 요청을 출발선에 세워 두었다가 한꺼번에 보낸다. */
    boolean runBurst() throws Exception {
        System.out.printf("QAS1 폭주: %d건을 한꺼번에 전송 (%s)%n", requests, url);
        Random rnd = new Random(seed);
        List<Result> planned = new ArrayList<>();
        List<String> bodies = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            Kind kind = pickKind(rnd);
            int tokens = kind.isLong() ? longTokens : between(rnd, shortTokensMin, shortTokensMax);
            Result r = new Result(i, kind, tokens);
            planned.add(r);
            bodies.add(json(text(r, rnd)));
        }
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(requests);
        startNanos = System.nanoTime();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < requests; i++) {
                Result r = planned.get(i);
                String body = bodies.get(i);
                pool.submit(() -> {
                    try {
                        gate.await();
                        send(r, body);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            startNanos = System.nanoTime();
            gate.countDown();
            progressUntil(done, requests, Long.MAX_VALUE);  // 요청마다 timeout-sec로 끊기므로 전체도 그 안에 끝난다
        }
        long wallMs = elapsedMs();
        if (!reported.compareAndSet(false, true)) return false;
        return reportBurst(new ArrayList<>(results), wallMs);
    }

    /** QAS2: 동시 C건을 유지한다. 앞 warmup 구간에 보낸 요청은 통계에서 뺀다. */
    boolean runSteady() throws Exception {
        System.out.printf("QAS2 지속 부하: 동시 %d건, 워밍업 %ds + 측정 %ds (%s)%n",
                concurrency, warmupSec, durationSec, url);
        long endMs = (warmupSec + durationSec) * 1000L;
        AtomicInteger seq = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(concurrency);
        startNanos = System.nanoTime();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int c = 0; c < concurrency; c++) {
                final int lane = c;
                pool.submit(() -> {
                    Random rnd = new Random(seed * 31 + lane);
                    try {
                        while (elapsedMs() < endMs) {
                            Kind kind = pickKind(rnd);
                            int tokens = kind.isLong() ? longTokens : between(rnd, shortTokensMin, shortTokensMax);
                            Result r = new Result(seq.getAndIncrement(), kind, tokens);
                            send(r, json(text(r, rnd)));
                        }
                    } finally {
                        done.countDown();
                    }
                });
            }
            // 측정이 끝나면 새 요청은 보내지 않는다. 진행 중 요청은 drain-sec까지만 기다리고 끊어 에러로 센다
            progressUntil(done, -1, endMs + drainSec * 1000L);
            if (done.getCount() > 0) {
                System.out.printf("  마감: 측정 종료 후 %d초가 지나 끝나지 않은 요청 %d건을 끊고 에러(drain-timeout)로 셉니다%n",
                        drainSec, active.size());
                abortActive("drain-timeout");
            }
        }
        long wallMs = elapsedMs();
        if (!reported.compareAndSet(false, true)) return false;
        return reportSteady(new ArrayList<>(results), wallMs);
    }

    /** 질문 하나를 보내고 SSE 응답을 끝까지 읽는다. 시간 초과 시 스트림을 닫아 끊는다. */
    void send(Result r, String body) {
        r.sentAtMs = elapsedMs();
        long t0 = System.nanoTime();
        int now = inFlight.incrementAndGet();
        maxInFlight.accumulateAndGet(now, Math::max);
        InputStream[] holder = new InputStream[1];
        active.put(r, holder);
        ScheduledFuture<?> killer = watchdog.schedule(() -> {
            InputStream in = holder[0];
            if (in != null) {
                try { in.close(); } catch (IOException ignored) { }
            }
        }, timeoutSec, TimeUnit.SECONDS);
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url + "/api/front-gateway/questions"))
                    .timeout(Duration.ofSeconds(timeoutSec))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<InputStream> res = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
            r.httpStatus = res.statusCode();
            holder[0] = res.body();
            if (r.httpStatus != 200) {
                r.error = "HTTP " + r.httpStatus;
                res.body().close();
                return;
            }
            readSse(r, res.body(), t0);
            if (r.doneMs < 0 && !r.failed && r.error.isEmpty()) {
                r.error = "stream-closed";
            }
        } catch (Exception e) {
            if (r.error.isEmpty()) {
                r.error = elapsedSince(t0) >= timeoutSec * 1000L ? "timeout" : e.getClass().getSimpleName();
            }
        } finally {
            killer.cancel(false);
            active.remove(r);
            inFlight.decrementAndGet();
            results.add(r);
        }
    }

    void readSse(Result r, InputStream in, long t0) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String event = "message";
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                event = "message";
            } else if (line.startsWith("event:")) {
                event = line.substring(6).trim();
            } else if (line.startsWith("data:")) {
                String data = line.substring(5);
                long t = elapsedSince(t0);
                switch (event) {
                    case "position" -> {
                        if (r.positionMs < 0) {
                            r.positionMs = t;
                            Matcher m = POSITION.matcher(data);
                            if (m.find()) r.initialPosition = Integer.parseInt(m.group(1));
                        }
                    }
                    case "token" -> {
                        if (r.firstTokenMs < 0) r.firstTokenMs = t;
                        if (r.answer != null) {
                            Matcher tm = TOKEN.matcher(data);
                            if (tm.find()) r.answer.append(unescape(tm.group(1)));
                        }
                        if (data.contains("\"isLast\":true")) {
                            r.doneMs = t;
                            return;
                        }
                    }
                    case "failure" -> {
                        r.failed = true;
                        r.error = "failure";
                        return;
                    }
                    default -> { }
                }
            }
        }
    }

    /** 끝나지 않은 요청에 이유를 적고 응답 스트림을 닫는다. 읽던 스레드는 곧 끝나 결과에 들어간다. */
    void abortActive(String reason) {
        for (Map.Entry<Result, InputStream[]> e : active.entrySet()) {
            Result r = e.getKey();
            if (r.error.isEmpty() && r.doneMs < 0 && !r.failed) r.error = reason;
            InputStream in = e.getValue()[0];
            if (in != null) {
                try { in.close(); } catch (IOException ignored) { }
            }
        }
    }

    /** Ctrl+C: 아직 요약하지 않았다면 끝난 요청과 끝나지 않은 요청(interrupted)을 합쳐 요약한다. */
    void reportOnInterrupt() {
        if (!reported.compareAndSet(false, true)) return;
        List<Result> rs = new ArrayList<>(results);
        for (Result r : active.keySet()) {
            if (r.error.isEmpty() && r.doneMs < 0 && !r.failed) r.error = "interrupted";
            if (!rs.contains(r)) rs.add(r);
        }
        System.out.printf("%n중단됨: 끝나지 않은 요청 %d건은 interrupted로 셉니다%n", active.size());
        try {
            if ("steady".equals(mode)) reportSteady(rs, elapsedMs());
            else reportBurst(rs, elapsedMs());
        } catch (IOException e) {
            System.out.println("요약 저장 실패: " + e.getMessage());
        }
    }

    void progressUntil(CountDownLatch done, int total, long deadlineMs) throws InterruptedException {
        while (!done.await(5, TimeUnit.SECONDS)) {
            if (elapsedMs() >= deadlineMs) return;
            System.out.printf("  %4ds  진행 중 %d건, 끝난 요청 %d건%s%n", elapsedMs() / 1000, inFlight.get(),
                    results.size(), total > 0 ? " / " + total : "");
        }
    }

    // ---- 보고 ----

    boolean reportBurst(List<Result> rs, long wallMs) throws IOException {
        rs.sort((x, y) -> Integer.compare(x.index, y.index));
        writeCsv(rs);
        Map<String, Integer> reasons = new HashMap<>();
        int errors = 0;
        int maxOkPos = -1;
        int minMissPos = Integer.MAX_VALUE;
        for (Result r : rs) {
            String e = r.qas1Error();
            if (e != null) {
                errors++;
                reasons.merge(e, 1, Integer::sum);
            }
            if (r.kind == Kind.SHORT && r.initialPosition > 0) {
                if (e == null) maxOkPos = Math.max(maxOkPos, r.initialPosition);
                else if (e.startsWith("short-ttft")) minMissPos = Math.min(minMissPos, r.initialPosition);
            }
        }
        double rate = rs.isEmpty() ? 1 : (double) errors / rs.size();
        boolean pass = rate <= QAS1_ERROR_RATE_LIMIT;

        System.out.println();
        System.out.println("===== QAS1 폭주 결과 =====");
        System.out.printf("요청 %d건 (짧은 %d / 긴 %d), 전체 소요 %.1f초, 최대 동시 연결 %d%n", rs.size(),
                count(rs, false), count(rs, true), wallMs / 1000.0, maxInFlight.get());
        printTtft("짧은 질문 첫 토큰", rs.stream().filter(r -> r.kind == Kind.SHORT).toList());
        printTtft("긴 입력 첫 토큰", rs.stream().filter(r -> r.kind.isLong()).toList());
        List<Long> longPos = rs.stream().filter(r -> r.kind.isLong() && r.positionMs >= 0).map(r -> r.positionMs).toList();
        System.out.printf("긴 입력 순번 안내 시간: p95 %s, 최대 %s (기준 1초)%n",
                fmt(percentile(longPos, 95)), fmt(longPos.isEmpty() ? -1 : Collections.max(longPos)));
        System.out.printf("에러 %d건 / %d건 = %.2f%% (기준 0.1%% 이하)%n", errors, rs.size(), rate * 100);
        reasons.forEach((k, v) -> System.out.printf("  - %s: %d건%n", k, v));
        if (maxOkPos > 0) System.out.printf("30초 안에 첫 토큰을 받은 짧은 질문의 최대 초기 순번: %d%n", maxOkPos);
        if (minMissPos != Integer.MAX_VALUE) System.out.printf("30초를 넘긴 짧은 질문의 최소 초기 순번: %d%n", minMissPos);
        reportAnswers(rs);
        System.out.println("판정: " + (pass ? "PASS" : "FAIL"));
        return pass;
    }

    boolean reportSteady(List<Result> all, long wallMs) throws IOException {
        all.sort((x, y) -> Integer.compare(x.index, y.index));
        writeCsv(all);
        long from = warmupSec * 1000L;
        List<Result> rs = all.stream().filter(r -> r.sentAtMs >= from).toList();
        List<Long> ttft = ttfts(rs);
        long p95 = percentile(ttft, 95);
        long missing = rs.stream().filter(r -> r.firstTokenMs < 0).count();
        // 첫 토큰을 못 받은 요청은 3초를 넘긴 것으로 센다
        List<Long> withMissing = new ArrayList<>(ttft);
        for (int i = 0; i < missing; i++) withMissing.add(Long.MAX_VALUE);
        long p95WithMissing = percentile(withMissing, 95);
        boolean pass = !rs.isEmpty() && p95WithMissing <= QAS2_P95_LIMIT_MS;

        System.out.println();
        System.out.println("===== QAS2 첫 응답 속도 결과 =====");
        System.out.printf("전체 %d건 중 측정 구간 %d건 (워밍업 %d건 제외), 전체 소요 %.1f초, 최대 동시 연결 %d%n",
                all.size(), rs.size(), all.size() - rs.size(), wallMs / 1000.0, maxInFlight.get());
        System.out.printf("측정 구간 처리량: %.2f건/초%n", rs.size() / Math.max(1.0, durationSec));
        printTtft("전체 첫 토큰", rs);
        printTtft("짧은 질문 첫 토큰", rs.stream().filter(r -> r.kind == Kind.SHORT).toList());
        printTtft("긴 입력 첫 토큰", rs.stream().filter(r -> r.kind.isLong()).toList());
        long within = ttft.stream().filter(v -> v <= QAS2_P95_LIMIT_MS).count();
        System.out.printf("3초 이내 첫 토큰 비율: %.1f%% (기준 95%% 이상), 첫 토큰 없음 %d건%n",
                rs.isEmpty() ? 0 : within * 100.0 / rs.size(), missing);
        reportAnswers(all);
        System.out.println("판정: " + (pass ? "PASS" : "FAIL"));
        return pass;
    }

    void printTtft(String label, List<Result> rs) {
        List<Long> v = ttfts(rs);
        System.out.printf("%s (%d건, 받은 %d건): p50 %s / p95 %s / p99 %s / 최대 %s%n", label, rs.size(), v.size(),
                fmt(percentile(v, 50)), fmt(percentile(v, 95)), fmt(percentile(v, 99)),
                fmt(v.isEmpty() ? -1 : Collections.max(v)));
    }

    /**
     * --check: 답이 제 요청의 것인지 확인한다. 다른 요청의 표식이 들어 있으면 답이 섞인 것(섞임 0건이어야 한다).
     * 자기 표식이 없는 것은 모델이 지시를 따르지 않은 것이라 섞임과 따로 센다. --answers: 답을 JSONL로 저장한다.
     */
    void reportAnswers(List<Result> rs) throws IOException {
        List<Result> done = rs.stream().filter(r -> r.answer != null && r.doneMs >= 0).toList();
        if (check) {
            int own = 0, foreign = 0, noTag = 0, sumOk = 0;
            List<Result> mixed = new ArrayList<>();
            for (Result r : done) {
                String ans = r.answer.toString();
                boolean hasOwn = ans.contains(r.tag);
                boolean hasForeign = false;
                Matcher m = TAG.matcher(ans);
                while (m.find()) {
                    if (Integer.parseInt(m.group(1)) != r.index) hasForeign = true;
                }
                if (hasForeign) { foreign++; mixed.add(r); }
                else if (hasOwn) own++;
                else noTag++;
                if (Pattern.compile("(?<!\\d)" + r.expectedSum + "(?!\\d)").matcher(ans).find()) sumOk++;
            }
            System.out.println();
            System.out.printf("답변 확인 (끝까지 받은 %d건): 자기 표식 %d건, 다른 요청 표식(섞임) %d건, 표식 없음(모델이 지시 무시) %d건%n",
                    done.size(), own, foreign, noTag);
            System.out.printf("덧셈 정답 포함: %d건 / %d건%n", sumOk, done.size());
            for (Result r : mixed.subList(0, Math.min(3, mixed.size()))) {
                System.out.printf("  섞임 의심 #%d %s: %s%n", r.index, r.tag, preview(r.answer.toString()));
            }
            System.out.println("판정(섞임): " + (foreign == 0 ? "PASS (다른 요청의 답이 섞이지 않음)" : "FAIL"));
        }
        if (!done.isEmpty()) {
            System.out.println("답변 예시:");
            for (Result r : done.subList(0, Math.min(3, done.size()))) {
                System.out.printf("  #%d (%s): %s%n", r.index, r.kind.label(), preview(r.answer.toString()));
            }
        }
        if (answersFile != null) {
            try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(Path.of(answersFile), StandardCharsets.UTF_8))) {
                for (Result r : rs) {
                    if (r.answer == null) continue;
                    w.printf(Locale.ROOT, "{\"index\":%d,\"kind\":\"%s\",\"tag\":%s,\"expectedSum\":%d,\"firstTokenMs\":%d,\"error\":\"%s\",\"answer\":%s}%n",
                            r.index, r.kind.label(), r.tag == null ? "null" : quote(r.tag), r.expectedSum, r.firstTokenMs,
                            r.error, quote(r.answer.toString()));
                }
            }
            System.out.println("답변 기록: " + answersFile);
        }
    }

    static String preview(String s) {
        String one = s.replaceAll("\\s+", " ").trim();
        return one.length() > 120 ? one.substring(0, 120) + "…" : one;
    }

    static String unescape(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) { b.append(c); continue; }
            char n = s.charAt(++i);
            switch (n) {
                case 'n' -> b.append('\n');
                case 't' -> b.append('\t');
                case 'r' -> b.append('\r');
                case 'b' -> b.append('\b');
                case 'f' -> b.append('\f');
                case 'u' -> {
                    if (i + 4 < s.length()) { b.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16)); i += 4; }
                }
                default -> b.append(n);
            }
        }
        return b.toString();
    }

    void writeCsv(List<Result> rs) throws IOException {
        if (csv == null) return;
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(Path.of(csv), StandardCharsets.UTF_8))) {
            w.println("index,kind,inputTokens,sentAtMs,httpStatus,positionMs,initialPosition,firstTokenMs,doneMs,failed,qas1Error,error");
            for (Result r : rs) {
                String e = r.qas1Error();
                w.printf(Locale.ROOT, "%d,%s,%d,%d,%d,%d,%d,%d,%d,%b,%s,%s%n", r.index, r.kind.label(), r.inputTokens,
                        r.sentAtMs, r.httpStatus, r.positionMs, r.initialPosition, r.firstTokenMs, r.doneMs, r.failed,
                        e == null ? "" : e, r.error);
            }
        }
        System.out.println("요청별 기록: " + csv);
    }

    // ---- 질문 본문 ----

    static final String[] WORDS = ("system queue worker token latency budget prefill decode cache memory request "
            + "answer model server cluster region network policy retry lease credit replica primary epoch fence "
            + "monitor alert metric throughput capacity schedule chunk batch stream gateway client session history "
            + "document summary section paragraph analysis report design module package interface adapter profile "
            + "student project course review feedback quality scenario failure recovery spare instance capacity "
            + "river mountain garden window market coffee travel weather library museum history science music "
            + "planet energy ocean forest city village bridge harbor train bicycle kitchen recipe festival").split(" ");

    String text(Result r, Random rnd) {
        Kind kind = r.kind;
        int tokens = r.inputTokens;
        if (check || answersFile != null) r.answer = new StringBuilder();
        int chars = tokens * charsPerToken;
        StringBuilder sb = new StringBuilder(chars + 64);
        switch (kind) {
            case SHORT -> sb.append("Question: ");
            case LONG_CHAT -> sb.append("Previous conversation follows. ");
            case LONG_DOC -> sb.append("Document follows. ");
        }
        int words = 0;
        while (sb.length() < chars) {
            String w = WORDS[rnd.nextInt(WORDS.length)];
            if (words % 12 == 0) {
                w = Character.toUpperCase(w.charAt(0)) + w.substring(1);
            }
            sb.append(w);
            words++;
            sb.append(words % 12 == 0 ? ". " : " ");
        }
        sb.append(switch (kind) {
            case SHORT -> " Please answer briefly.";
            case LONG_CHAT -> " Based on the conversation above, answer the last question briefly.";
            case LONG_DOC -> " Summarize the document above in three sentences.";
        });
        if (check) {
            // 답이 다른 요청과 섞이지 않았는지 확인할 표식과, 내용이 맞는지 확인할 덧셈 문제를 붙인다
            int a = between(rnd, 10, 99);
            int b = between(rnd, 10, 99);
            r.tag = "[Q" + r.index + "]";
            r.expectedSum = a + b;
            sb.append(" Finally, start your reply with the tag ").append(r.tag)
                    .append(" exactly, then answer this first: what is ").append(a).append(" + ").append(b).append("?");
        }
        return sb.toString();
    }

    static Kind pickKind(Random rnd) {
        int p = rnd.nextInt(100);
        if (p < 80) return Kind.SHORT;
        if (p < 95) return Kind.LONG_CHAT;
        return Kind.LONG_DOC;
    }

    // ---- 도우미 ----

    long elapsedMs() { return (System.nanoTime() - startNanos) / 1_000_000; }

    static long elapsedSince(long t0) { return (System.nanoTime() - t0) / 1_000_000; }

    static int between(Random rnd, int lo, int hi) { return hi <= lo ? lo : lo + rnd.nextInt(hi - lo + 1); }

    static long count(List<Result> rs, boolean isLong) { return rs.stream().filter(r -> r.kind.isLong() == isLong).count(); }

    static List<Long> ttfts(List<Result> rs) {
        return rs.stream().filter(r -> r.firstTokenMs >= 0).map(r -> r.firstTokenMs).sorted().toList();
    }

    static long percentile(List<Long> values, int p) {
        if (values.isEmpty()) return -1;
        List<Long> v = new ArrayList<>(values);
        Collections.sort(v);
        int idx = (int) Math.ceil(p / 100.0 * v.size()) - 1;
        return v.get(Math.max(0, Math.min(idx, v.size() - 1)));
    }

    static String fmt(long ms) {
        if (ms < 0) return "-";
        if (ms == Long.MAX_VALUE) return "없음";
        return ms < 10_000 ? ms + "ms" : String.format(Locale.ROOT, "%.1fs", ms / 1000.0);
    }

    static String json(String text) {
        StringBuilder sb = new StringBuilder(text.length() + 16).append("{\"text\":\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                default -> sb.append(c);
            }
        }
        return sb.append("\"}").toString();
    }

    /** 문자열 하나를 JSON 문자열 값으로 (따옴표 포함). 답변 기록에 쓴다. */
    static String quote(String text) {
        StringBuilder sb = new StringBuilder(text.length() + 8).append('"');
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    static Map<String, String> parseArgs(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) throw new IllegalArgumentException("알 수 없는 인자: " + args[i]);
            String key = args[i].substring(2);
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) m.put(key, args[++i]);
            else m.put(key, "true");
        }
        return m;
    }

    static void usage() {
        System.out.println("""
                사용법: java scripts/loadtest/LoadTest.java [옵션]
                  --mode burst|steady      QAS1 폭주 / QAS2 지속 부하 (기본 burst)
                  --url URL                Front Gateway 주소 (기본 http://localhost:8080)
                  --requests N             burst: 한꺼번에 보낼 요청 수 (기본 1000)
                  --concurrency C          steady: 유지할 동시 요청 수 (기본 160)
                  --duration-sec D         steady: 측정 구간 (기본 300)
                  --warmup-sec W           steady: 통계에서 뺄 앞 구간 (기본 60)
                  --long-tokens T          긴 입력 길이 (기본 4000)
                  --short-tokens-min/max   짧은 질문 길이 범위 (기본 20~500)
                  --chars-per-token K      토큰당 글자 수 (기본 4, front-gateway와 같게)
                  --seed S                 짧은/긴 구성과 본문 난수 (기본 42)
                  --timeout-sec S          요청 하나의 최대 대기 (기본 600)
                  --drain-sec S            steady: 측정이 끝난 뒤 진행 중 요청을 기다리는 최대 시간 (기본 60, 넘으면 에러로 셈)
                  --csv PATH               요청별 기록 저장
                  --check                  질문마다 표식 [Q번호]와 덧셈 문제를 붙여, 답이 다른 요청과 섞이지 않았는지·내용이 맞는지 확인
                  --answers PATH           받은 답변 전체를 JSONL로 저장 (질문 번호, 종류, 첫 토큰 시간, 답변)
                  --fail-on-fail           FAIL이면 종료 코드 1
                """);
    }
}
