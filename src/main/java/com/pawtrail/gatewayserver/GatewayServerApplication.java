package com.pawtrail.gatewayserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * API 게이트웨이입니다.
 *
 * 브라우저에서 오는 모든 요청이 이곳을 지납니다. 요청 경로를 보고 어느 서비스로 보낼지
 * 정하며, 대상 서비스의 실제 주소는 유레카에서 받습니다.
 *
 * 인증도 여기서 끝납니다. 쿠키에 담긴 토큰의 서명을 확인한 뒤 사용자 정보를
 * X-User-Id 와 X-User-Role 헤더로 바꿔 넣어 주고, 뒤쪽 서비스들은 토큰을 다루지 않습니다.
 * 검증을 인증 서비스에 맡기지 않은 것은 그렇게 하면 모든 요청이 그곳을 한 번 더 거쳐
 * 병목이자 단일 장애점이 되기 때문입니다.
 *
 * 라우팅 규칙과 공개키는 config 저장소의 gateway-server.yml 에서 내려옵니다.
 * 서비스가 하나씩 완성될 때마다 라우트를 열어야 하는데, 규칙이 이 저장소에 있으면
 * 그때마다 이미지를 다시 만들어 배포해야 하므로 설정으로 뺐습니다.
 *
 * 별도의 활성화 애노테이션이 필요하지 않습니다. 설정 서버나 서비스 레지스트리와 달리
 * 의존성이 클래스패스에 있으면 자동으로 구성됩니다.
 *
 * 공통 모듈(com.pawtrail.common)을 의존하지 않습니다.
 * 플랫폼 3종에 공통으로 적용하는 규칙이며, 이 서비스에는 이유가 하나 더 있습니다.
 * 공통 모듈은 서블릿 환경을 전제로 만들어졌고 이 서비스는 WebFlux 로 돕니다.
 * 자세한 내용은 README 4장에 있습니다.
 */
@SpringBootApplication
public class GatewayServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayServerApplication.class, args);
    }

}
