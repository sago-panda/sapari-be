// CORS 프리플라이트를 엣지에서 끝낸다.
//
// OAC 가 모든 오리진 요청에 AWS 서명을 붙이기 때문에, OPTIONS 도 S3 에서
// "인증된 요청"으로 판정된다. 그러면 버킷 CORS 설정이 아니라 버킷 정책으로
// 평가되고, 정책은 s3:GetObject 만 허용하므로 403 이 된다.
// 버킷에 CORS 를 설정해도 이 경로에서는 효과가 없다.
//
// 프리플라이트는 실제 파일이 필요 없는 질의라 오리진까지 갈 이유가 없다.
// 여기서 바로 204 로 답한다.
function handler(event) {
  var request = event.request;

  if (request.method !== 'OPTIONS') {
    return request;
  }

  // 값은 response_headers.tf 의 CORS 정책과 같이 유지할 것.
  // 한쪽만 좁히면 프리플라이트는 통과하는데 본 요청이 막히는 상태가 된다.
  return {
    statusCode: 204,
    statusDescription: 'No Content',
    headers: {
      'access-control-allow-origin': { value: '*' },
      'access-control-allow-methods': { value: 'GET, HEAD, OPTIONS' },
      'access-control-allow-headers': { value: '*' },
      'access-control-max-age': { value: '3600' }
    }
  };
}
