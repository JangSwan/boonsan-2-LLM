// 브라우저가 직접 부르는 앱 주소 (호스트에서 접근하므로 compose 포트를 쓴다).
// 다른 주소를 쓰려면 이 파일만 바꾼다.
window.LLM_CONFIG = Object.assign({
  frontGatewayUrl: 'http://localhost:8080',
  modelManagerUrl: 'http://localhost:8110',
  monitoringUrl: 'http://localhost:8120',
  pollIntervalMs: 2000,
}, window.LLM_CONFIG || {});
