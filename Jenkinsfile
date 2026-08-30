// 파이프라인 본체는 Jenkins 공유 라이브러리에 있습니다.
// 이 파일에서는 파라미터 세 개만 채웁니다.
//
//   serviceName  서비스명 (레포명과 동일하게)
//   deployNode   배포 노드. edge / core / app 중 하나 (README 분류표 참고)
//   instances    띄울 인스턴스 개수
//
// 플랫폼 3종은 edge 노드에 배치합니다(nginx, gateway, eureka, config).
// 다만 배포 방식은 서로 다릅니다.
//   gateway  nginx blue-green
//   eureka   단독 교체
//   config   재시작
//
// 이 서비스는 nginx upstream 전환 방식입니다.
// 새 인스턴스를 띄워 정상 여부를 확인한 뒤 nginx 가 바라보는 대상을 바꿉니다.
// 새 것이 뜨지 않으면 대상을 바꾸지 않으면 그만이므로 되돌릴 것이 없습니다.
// 배포 순서에서는 core 와 app 다음, nginx 앞에 옵니다.

@Library('pawtrail-pipeline') _

springServicePipeline(
    serviceName: 'gateway-server',
    deployNode : 'edge',
    instances  : 1
)
