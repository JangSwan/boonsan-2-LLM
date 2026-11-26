/**
 * 답변 월 (시연 도구, EA 화면 아님): 타일마다 서로 다른 질문을 보내고, 순번 안내 → 첫 토큰 → 답변이
 * 동시에 흘러나오는 모습을 보여 준다. 답이 끝나면 잠시 뒤 다음 질문을 보낸다. 업무 판단은 하지 않는다.
 */
const WALL_QUESTIONS = [
  '대한민국의 수도는 어디이고, 그 도시의 특징을 두 문장으로 알려줘.',
  '사과와 배의 차이를 세 가지로 정리해줘.',
  '17 곱하기 23은 얼마야? 계산 과정도 보여줘.',
  '좋은 아침 습관 세 가지를 추천해줘.',
  '파이썬과 자바의 차이를 짧게 설명해줘.',
  '고양이가 상자를 좋아하는 이유를 설명해줘.',
  '비가 오는 날 실내에서 할 수 있는 활동 다섯 가지를 알려줘.',
  '광합성을 초등학생에게 설명하듯 말해줘.',
  '소프트웨어 아키텍처에서 품질 속성이 무엇인지 설명해줘.',
  '큐(queue)와 스택(stack)의 차이를 예시와 함께 알려줘.',
  '지구에서 달까지의 거리는 대략 얼마야?',
  '시험 전날 효과적으로 복습하는 방법을 알려줘.',
  '김치찌개를 맛있게 끓이는 요령을 세 가지 알려줘.',
  'HTTP와 HTTPS의 차이를 한 문단으로 설명해줘.',
  '봄, 여름, 가을, 겨울을 각각 한 단어로 표현해줘.',
  '1부터 10까지 더하면 얼마야?',
  '운동을 꾸준히 하는 팁을 알려줘.',
  '로드 밸런서가 하는 일을 쉽게 설명해줘.',
  '바다가 파란 이유를 설명해줘.',
  '영어 공부를 시작하는 사람에게 조언 한마디 해줘.',
  '캐시(cache)가 무엇인지 비유를 들어 설명해줘.',
  '여행 짐을 쌀 때 빠뜨리기 쉬운 물건을 알려줘.',
  '"감사합니다"를 영어, 일본어, 중국어로 알려줘.',
  '좋은 팀 프로젝트 회의를 위한 규칙 세 가지를 제안해줘.',
  '컴퓨터의 CPU와 GPU는 어떻게 다른지 설명해줘.',
  '하루에 물을 얼마나 마시는 게 좋은지 알려줘.',
  '짧은 응원 메시지를 하나 써줘.',
  '장애 복구 시간(RTO)이 무엇인지 설명해줘.',
  '책을 빨리 읽는 방법이 있을까?',
  '3, 5, 8, 13 다음 숫자는 무엇이고 왜 그런지 알려줘.',
  '가을에 가볼 만한 국내 여행지를 세 곳 추천해줘.',
  '대기열(queue)에서 순번을 안내하는 것이 왜 중요한지 설명해줘.',
];

/** 보고서의 긴 입력(약 4,000토큰): 대화 누적·문서 붙여넣기를 흉내 낸 본문 */
const WALL_LONG = '다음은 지난 회의 기록입니다. 서비스 이용자가 늘면서 응답 지연과 대기열 관리가 중요해졌고, '
  .repeat(90) + '\n위 기록을 세 문장으로 요약해줘.';

class AnswerTile {
  constructor(wall, index, baseUrl) {
    this.wall = wall;
    this.index = index;
    this.el = document.createElement('article');
    this.el.className = 'tile';
    this.el.innerHTML = '<header><b class="q"></b><span class="st"></span></header><div class="a"></div><footer class="m"></footer>';
    this.q = this.el.querySelector('.q');
    this.st = this.el.querySelector('.st');
    this.a = this.el.querySelector('.a');
    this.m = this.el.querySelector('.m');
    this.host = new URL(baseUrl).host;
    this.sse = new SseClient(baseUrl, (e) => this.onEvent(e));
    this.round = 0;
  }

  async run() {
    while (this.wall.running) {
      const long = this.wall.mixLong.checked && Math.random() < 0.2;
      const question = long ? WALL_LONG : WALL_QUESTIONS[(this.index * 7 + this.round++) % WALL_QUESTIONS.length];
      await this.ask(question, long);
      if (this.wall.running) await new Promise((r) => setTimeout(r, 1500));
    }
  }

  async ask(question, long) {
    this.q.textContent = long ? `[긴 입력 약 4,000토큰] ${question.slice(-16)}` : question;
    this.a.textContent = '';
    this.el.dataset.kind = 'pending';
    this.setStatus('접수 중');
    this.started = performance.now();
    this.first = null;
    this.tokens = 0;
    this.requestId = '';
    this.closed = false;
    this.wall.change('running', +1);
    try {
      await this.sse.open(question);
      if (!this.closed) this.end('연결 닫힘', 'failed');
    } catch (e) {
      if (!this.closed) this.end('연결 실패', 'failed');
    }
  }

  onEvent(event) {
    if (event.name === 'position') {
      this.setStatus(`대기 ${event.data.position}번째`);
    } else if (event.name === 'token') {
      const c = event.data;
      if (this.first === null) {
        this.first = performance.now() - this.started;
        this.requestId = c.requestId.slice(0, 8);
        this.el.dataset.kind = 'streaming';
        this.setStatus('생성 중');
        this.wall.recordFirst(this.first);
      }
      this.tokens += 1;
      this.a.textContent += c.token;
      this.a.scrollTop = this.a.scrollHeight;
      this.meta();
      if (c.isLast) this.end('완료', 'done');
    } else if (event.name === 'failure') {
      this.a.textContent = event.data.message || '요청을 처리하지 못했습니다.';
      this.end('실패', 'failed');
    }
  }

  end(text, kind) {
    if (this.closed) return;
    this.closed = true;
    this.sse.close();
    this.el.dataset.kind = kind;
    this.setStatus(text);
    this.meta();
    this.wall.change('running', -1);
    if (kind !== 'stopped') this.wall.change(kind === 'done' ? 'done' : 'failed', +1);
  }

  meta() {
    const first = this.first === null ? '–' : `${(this.first / 1000).toFixed(2)}초`;
    this.m.textContent = `#${this.index + 1} · ${this.host} · 요청 ${this.requestId || '–'} · 첫 토큰 ${first} · 토큰 ${this.tokens}`;
  }

  setStatus(text) {
    this.st.textContent = text;
  }
}

class AnswerWall {
  constructor(root) {
    this.root = root;
    this.grid = root.querySelector('#wall');
    this.mixLong = root.querySelector('#mix-long');
    this.startButton = root.querySelector('#start');
    this.stopButton = root.querySelector('#stop');
    this.counts = { running: 0, done: 0, failed: 0 };
    this.firsts = [];
    this.running = false;
    this.startButton.addEventListener('click', () => this.start());
    if (this.baseUrls().length === 1) {
      // 클라우드 주소에서는 6개까지만 동시에 흐른다. 12 선택지를 감추고 안내 문구를 바꾼다
      const tiles = root.querySelector('#tiles');
      [...tiles.options].forEach((o) => { if (Number(o.value) > 6) o.remove(); });
      tiles.value = '6';
      root.querySelector('.wall-note').textContent =
        '브라우저는 같은 주소로 동시에 6개까지만 연결하므로 이 주소에서는 타일 6개까지 보여 준다. 더 큰 부하는 부하 생성기(scripts/loadtest)로 함께 건다. PC를 한 대 더 쓰면 6개를 더 볼 수 있다.';
    }
    this.stopButton.addEventListener('click', () => this.stop());
  }

  /**
   * 같은 주소로는 브라우저가 6개까지만 동시에 연결하므로 로컬에서는 localhost와 127.0.0.1로 나눈다.
   * 클라우드 서버처럼 다른 주소로 열었을 때는 나눌 다른 이름이 없으므로 주소 하나만 쓴다 (타일 최대 6개).
   */
  baseUrls() {
    const base = new URL(window.LLM_CONFIG.frontGatewayUrl);
    const alt = new URL(base);
    if (base.hostname === 'localhost') alt.hostname = '127.0.0.1';
    else if (base.hostname === '127.0.0.1') alt.hostname = 'localhost';
    else return [base.origin];
    return [base.origin, alt.origin];
  }

  maxTiles() {
    return this.baseUrls().length * 6;
  }

  start() {
    const urls = this.baseUrls();
    const n = Math.min(Number(this.root.querySelector('#tiles').value) || this.maxTiles(), this.maxTiles());
    this.grid.innerHTML = '';
    this.counts = { running: 0, done: 0, failed: 0 };
    this.firsts = [];
    this.render();
    this.running = true;
    this.startButton.hidden = true;
    this.stopButton.hidden = false;
    this.tiles = Array.from({ length: n }, (_, i) => new AnswerTile(this, i, urls[Math.min(Math.floor(i / 6), urls.length - 1)]));
    this.tiles.forEach((t, i) => {
      this.grid.appendChild(t.el);
      setTimeout(() => t.run(), i * 150);
    });
  }

  stop() {
    this.running = false;
    this.startButton.hidden = false;
    this.stopButton.hidden = true;
    (this.tiles || []).forEach((t) => t.end('멈춤', 'stopped'));
  }

  change(key, delta) {
    this.counts[key] += delta;
    this.render();
  }

  recordFirst(ms) {
    this.firsts.push(ms);
    this.render();
  }

  render() {
    const $ = (id) => this.root.querySelector(id);
    $('#s-running').textContent = this.counts.running;
    $('#s-done').textContent = this.counts.done;
    $('#s-failed').textContent = this.counts.failed;
    if (this.firsts.length) {
      const avg = this.firsts.reduce((a, b) => a + b, 0) / this.firsts.length;
      $('#s-avg').textContent = `${(avg / 1000).toFixed(2)}초`;
      $('#s-max').textContent = `${(Math.max(...this.firsts) / 1000).toFixed(2)}초`;
    } else {
      $('#s-avg').textContent = '–';
      $('#s-max').textContent = '–';
    }
  }
}

window.addEventListener('DOMContentLoaded', () => {
  const wall = new AnswerWall(document);
  // 시연용 바로 시작: wall.html?start&tiles=12&long
  const p = new URLSearchParams(location.search);
  if (p.has('tiles')) {
    const tiles = document.querySelector('#tiles');
    tiles.value = p.get('tiles');
    // 이 주소에서 고를 수 없는 개수(예: 클라우드 주소의 12)면 고를 수 있는 가장 큰 값으로
    if (!tiles.value) tiles.value = tiles.options[tiles.options.length - 1].value;
  }
  if (p.has('long')) wall.mixLong.checked = true;
  if (p.has('start')) wall.start();
});
