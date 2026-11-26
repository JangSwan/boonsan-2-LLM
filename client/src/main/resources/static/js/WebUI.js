/**
 * WebUI (EA boundary): 이용자 질문 화면. sd1 1·9·23번, sd4 17번 화면 표시.
 */
class WebUI {
  constructor(root) {
    this.form = root.querySelector('#question-form');
    this.input = root.querySelector('#question');
    this.sendButton = root.querySelector('#send');
    this.stopButton = root.querySelector('#stop');
    this.status = root.querySelector('#status');
    this.answer = root.querySelector('#answer');
    this.meta = root.querySelector('#meta');
    this.sse = new SseClient(window.LLM_CONFIG.frontGatewayUrl, (event) => this.onEvent(event));

    this.form.addEventListener('submit', (e) => {
      e.preventDefault();
      this.sendQuestion(this.input.value);
    });
    this.stopButton.addEventListener('click', () => this.finish('중단했습니다.'));
    root.querySelector('#sample-long').addEventListener('click', () => {
      // 보고서의 긴 입력(약 4,000토큰). 실제 Llama 토크나이저는 한글 한 글자를 대략 1토큰 안팎으로 세므로
      // 약 4,500자로 둔다. real 엔진의 최대 길이(max-model-len 8192)를 넘지 않는다.
      this.input.value = '긴 문서 요약을 부탁합니다. '.repeat(300);
      this.updateCount();
    });
    root.querySelector('#sample-crash').addEventListener('click', () => {
      this.input.value = '[[crash]] 엔진을 비정상 종료시키는 질문 (sd4 장애 주입)';
      this.updateCount();
    });
    this.input.addEventListener('input', () => this.updateCount());
    this.updateCount();
  }

  async sendQuestion(text) {
    if (!text.trim()) return;
    this.answer.textContent = '';
    this.answer.classList.remove('failed');
    this.meta.textContent = '';
    this.startedAt = performance.now();
    this.firstTokenAt = null;
    this.tokens = 0;
    this.setBusy(true);
    this.setStatus('접수 중…', 'pending');
    try {
      await this.sse.open(text);
      if (this.busy) this.finish('연결이 닫혔습니다.');
    } catch (e) {
      this.showFailure(null, '연결 실패: ' + e.message);
    }
  }

  onEvent(event) {
    switch (event.name) {
      case 'position': this.showQueuePosition(event.data.position); break;
      case 'token': this.showToken(event.data); break;
      case 'failure': this.showFailure(event.data.requestId, event.data.message); break;
      default: break;
    }
  }

  showQueuePosition(position) {
    this.setStatus(`대기 ${position}번째`, 'pending');
  }

  /** @param {{requestId: string, seq: number, token: string, isFirst: boolean, isLast: boolean}} chunk */
  showToken(chunk) {
    if (this.firstTokenAt === null) {
      this.firstTokenAt = performance.now();
      this.setStatus('답변 생성 중', 'streaming');
    }
    this.tokens += 1;
    this.answer.textContent += chunk.token;
    this.meta.textContent = `요청 ${chunk.requestId.slice(0, 8)} · 첫 토큰 ${this.ms(this.firstTokenAt)} · 토큰 ${this.tokens}개`;
    if (chunk.isLast) this.finish(`완료 · 전체 ${this.ms(performance.now())}`);
  }

  showFailure(requestId, message) {
    this.answer.classList.add('failed');
    this.answer.textContent = message || '요청을 처리하지 못했습니다.';
    this.finish(requestId ? `실패 · 요청 ${requestId.slice(0, 8)}` : '실패', 'failed');
  }

  finish(text, kind = 'done') {
    this.sse.close();
    this.setBusy(false);
    this.setStatus(text, kind);
  }

  setBusy(busy) {
    this.busy = busy;
    this.sendButton.disabled = busy;
    this.stopButton.hidden = !busy;
  }

  setStatus(text, kind) {
    this.status.textContent = text;
    this.status.dataset.kind = kind;
  }

  updateCount() {
    const chars = [...this.input.value];
    // 대략의 토큰 수: 한글은 한 글자를 1토큰, 나머지(영문·숫자·공백)는 4글자를 1토큰으로 센다 (실제 Llama 토크나이저 근사)
    const hangul = chars.filter((c) => /[\uAC00-\uD7A3\u3131-\u318E]/.test(c)).length;
    const tokens = hangul + Math.ceil((chars.length - hangul) / 4);
    this.form.querySelector('#count').textContent = `${chars.length.toLocaleString()}자 · 약 ${tokens.toLocaleString()}토큰`;
  }

  ms(at) {
    return `${((at - this.startedAt) / 1000).toFixed(2)}초`;
  }
}

window.addEventListener('DOMContentLoaded', () => new WebUI(document));
