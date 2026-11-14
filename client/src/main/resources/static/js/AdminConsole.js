/**
 * AdminConsole (EA boundary): 운영자 화면. 모델 교체 요청·상태(sd6), 알림(sd3), 처리 현황.
 * Model Manager는 다른 서브시스템을 호출하지 않으므로 교체·롤백 상태는 여기서 주기적으로 조회한다.
 */
class AdminConsole {
  constructor(root) {
    this.cfg = window.LLM_CONFIG;
    this.swapForm = root.querySelector('#swap-form');
    this.swapResult = root.querySelector('#swap-result');
    this.swapStatus = root.querySelector('#swap-status');
    this.alerts = root.querySelector('#alerts');
    this.metrics = root.querySelector('#metrics');
    this.queue = root.querySelector('#queue');

    this.swapForm.addEventListener('submit', (e) => {
      e.preventDefault();
      const data = new FormData(this.swapForm);
      this.requestModelSwap(data.get('model'), data.get('stage'));
    });
    root.querySelector('#degrade').addEventListener('click', () => this.injectLowQuality());

    const tick = () => { this.showSwapStatus(); this.showAlerts(); this.showMetrics(); };
    tick();
    setInterval(tick, this.cfg.pollIntervalMs);
  }

  async requestModelSwap(model, stage) {
    const response = await fetch(this.cfg.modelManagerUrl + '/api/model-manager/swaps', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ model, stage }),
    }).catch((e) => ({ ok: false, json: async () => ({ message: e.message }) }));
    const body = await response.json().catch(() => ({}));
    this.swapResult.textContent = response.ok ? `${stage} 교체를 시작했습니다.` : `거부: ${body.message || response.status}`;
    this.swapResult.dataset.kind = response.ok ? 'ok' : 'error';
    this.showSwapStatus();
  }

  async showSwapStatus() {
    const s = await this.get(this.cfg.modelManagerUrl + '/api/model-manager/swaps/status');
    if (!s) { this.swapStatus.innerHTML = '<p class="muted">Model Manager 응답 없음</p>'; return; }
    const pct = Math.round(s.ratio * 100);
    this.swapStatus.innerHTML = `
      <div class="kv"><span>상태</span><b data-status="${s.status}">${s.status}</b></div>
      <div class="kv"><span>활성</span><b>${s.activeModel} · ${s.activeStage}</b></div>
      <div class="kv"><span>목표</span><b>${s.targetStage || '—'}</b></div>
      <div class="kv"><span>v2 비율</span><b>${pct}%</b></div>
      <div class="bar"><i style="width:${pct}%"></i></div>
      <p class="muted">갱신 ${new Date(s.updatedAt).toLocaleTimeString()}</p>`;
  }

  async showAlerts() {
    const alerts = await this.get(this.cfg.monitoringUrl + '/api/monitoring/alerts');
    if (!alerts) { this.alerts.innerHTML = '<li class="muted">Monitoring 응답 없음</li>'; return; }
    this.alerts.innerHTML = alerts.length
      ? alerts.slice(0, 20).map((a) => `<li data-kind="${a.kind}"><time>${new Date(a.at).toLocaleTimeString()}</time><code>${a.kind}</code>${this.escape(a.message)}</li>`).join('')
      : '<li class="muted">알림 없음</li>';
  }

  async showMetrics() {
    const metrics = await this.get(this.cfg.monitoringUrl + '/api/monitoring/metrics');
    if (!metrics) return;
    const value = (name) => (metrics.find((m) => m.name === name) || {}).value;
    this.queue.innerHTML = ['queue.length', 'queue.active-leases', 'queue.concurrency-cap', 'queue.quarantined', 'workers.responding']
      .map((n) => `<div class="stat"><b>${value(n) ?? '—'}</b><span>${n.replace('queue.', '')}</span></div>`).join('');
    const workers = {};
    for (const m of metrics) {
      const match = m.name.match(/^worker\.([^.]+)\.(.+)$/);
      if (match) (workers[match[1]] ||= {})[match[2]] = m.value;
    }
    this.metrics.innerHTML = Object.entries(workers).sort().map(([id, w]) => `
      <tr><td>${id}</td><td>${w.sessions ?? '—'}</td><td>${w['unstarted-tokens'] ?? '—'}</td>
      <td>${w['step-budget'] ?? '—'}</td><td>${w['ttft-p95-ms'] ?? '—'}</td>
      <td>${w.gpu !== undefined ? Math.round(w.gpu * 100) + '%' : '—'}</td></tr>`).join('')
      || '<tr><td colspan="6" class="muted">수집된 워커 지표 없음</td></tr>';
  }

  /** 시연: 목표(v2) 버전에 낮은 품질 샘플을 넣어 롤백을 일으킨다 (sd6 26~27번). */
  async injectLowQuality() {
    const ratio = await this.get(this.cfg.modelManagerUrl + '/api/model-manager/ratio');
    const version = (ratio && (ratio.targetVersion || ratio.activeVersion)) || 'v2';
    for (let i = 0; i < 5; i++) {
      await fetch(this.cfg.monitoringUrl + '/api/monitoring/quality-samples', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ requestId: 'admin-sample-' + i, versionTag: version, score: 0.2 }),
      }).catch(() => {});
    }
    this.swapResult.textContent = `${version}에 낮은 품질 샘플 5건을 넣었습니다.`;
    this.swapResult.dataset.kind = 'ok';
  }

  async get(url) {
    try {
      const response = await fetch(url);
      return response.ok ? await response.json() : null;
    } catch (_) {
      return null;
    }
  }

  escape(text) {
    return String(text).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  }
}

window.addEventListener('DOMContentLoaded', () => new AdminConsole(document));
