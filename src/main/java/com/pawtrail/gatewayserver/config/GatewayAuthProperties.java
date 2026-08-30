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
}
