// 브라우저가 직접 부르는 앱 주소.
// - localhost/127.0.0.1에서 열면 compose 포트를 그대로 쓴다 (로컬 실행, SSH 터널).
// - 다른 주소(예: 클라우드 서버 공인 IP)에서 열면 같은 호스트의 각 포트를 쓴다.
//   클라우드 서버는 켤 때마다 공인 IP가 바뀌므로 주소를 고정하지 않는다 (docs/cloud-setup.md).
// 다른 주소를 쓰려면 이 파일만 바꾼다.
(function () {
  const host = window.location.hostname;
  const local = host === 'localhost' || host === '127.0.0.1' || host === '';
  const base = local ? 'http://localhost' : `${window.location.protocol}//${host}`;
  window.LLM_CONFIG = Object.assign({
    frontGatewayUrl: `${base}:8080`,
    modelManagerUrl: `${base}:8110`,
    monitoringUrl: `${base}:8120`,
    pollIntervalMs: 2000,
  }, window.LLM_CONFIG || {});
})();
