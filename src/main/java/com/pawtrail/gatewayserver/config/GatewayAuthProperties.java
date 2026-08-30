package com.pawtrail.gatewayserver.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 인증 없이 통과시킬 경로임
 * config 저장소의 gateway-server.yml 에서 내려옴
 *
 * 여기 없는 경로는 전부 토큰을 확인함
 *
 * * 반대 방식(전부 열고 몇 개만 막기)을 쓰지 않는 이유
 *   새 경로를 목록에 넣지 않았을 때
 *   지금 방식은 401 이 나서 바로 드러나지만
 *   반대 방식은 인증 없이 열려 버림
 *
 * @param permitAll 경로 패턴 목록임, ** 와 {변수} 를 쓸 수 있음
 */
@ConfigurationProperties(prefix = "app.gateway")
public record GatewayAuthProperties(List<String> permitAll) {

    /**
     * 목록이 비어 있으면 기동을 막음
     *
     * 빈 목록은 "열어 둘 경로가 없다" 가 아니라 **설정을 못 받았다** 는 뜻임
     * 로그인·회원가입까지 토큰을 요구하게 되어 아무도 로그인할 수 없는 상태가 되는데,
     * 그때 나타나는 증상이 "모든 요청이 401" 이라 원인을 설정 누락으로 짚기 어려움
     *
     * * null 로 두고 넘어가면 필터 생성자에서 NPE 가 나며
     *   그쪽 메시지는 설정 문제라는 것을 알려주지 않음
     *   여기서 막으면 무엇이 비었는지가 기동 로그에 그대로 적힘
     */
    public GatewayAuthProperties {
        if (permitAll == null || permitAll.isEmpty()) {
            throw new IllegalStateException(
                    "app.gateway.permit-all 이 비어 있습니다. config 저장소의 gateway-server.yml 을 확인하십시오");
        }
    }
}
