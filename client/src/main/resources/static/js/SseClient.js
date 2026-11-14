/**
 * SseClient (EA boundary): SSE 연결로 순번 안내와 토큰 스트림을 받는다.
 * 질문은 긴 입력을 담으므로 fetch()의 POST로 보내고, 응답 본문을 SSE 형식으로 읽는다
 * (EventSource는 GET만 되어 쓰지 않는다).
 */
class SseClient {
  /** @param {(event: {name: string, data: any}) => void} onEvent */
  constructor(baseUrl, onEvent) {
    this.baseUrl = baseUrl;
    this.handler = onEvent;
    this.controller = null;
  }

  /** 질문을 보내고 스트림을 연다. 스트림이 끝나면 resolve된다. */
  async open(question) {
    this.close();
    this.controller = new AbortController();
    const response = await fetch(this.baseUrl + '/api/front-gateway/questions', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
      body: JSON.stringify({ text: question }),
      signal: this.controller.signal,
    });
    if (!response.ok) {
      const error = await response.json().catch(() => ({ code: 'HTTP_' + response.status, message: response.statusText }));
      this.onEvent('failure', error);
      return;
    }
    const reader = response.body.getReader();
    const decoder = new TextDecoder('utf-8');
    let buffer = '';
    try {
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n');
        let end;
        while ((end = buffer.indexOf('\n\n')) >= 0) {
          this.dispatch(buffer.slice(0, end));
          buffer = buffer.slice(end + 2);
        }
      }
      if (buffer.trim()) this.dispatch(buffer);
    } catch (e) {
      if (e.name !== 'AbortError') throw e;
    }
  }

  /** 이벤트 하나를 화면에 넘긴다. */
  onEvent(event, data) {
    this.handler({ name: event, data });
  }

  close() {
    if (this.controller) {
      this.controller.abort();
      this.controller = null;
    }
  }

  dispatch(block) {
    let name = 'message';
    const data = [];
    for (const line of block.split('\n')) {
      if (line.startsWith('event:')) name = line.slice(6).trim();
      else if (line.startsWith('data:')) data.push(line.slice(5));
    }
    if (!data.length) return;
    let parsed = data.join('\n');
    try { parsed = JSON.parse(parsed); } catch (_) { /* 문자열 그대로 */ }
    this.onEvent(name, parsed);
  }
}
