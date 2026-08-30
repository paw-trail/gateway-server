package com.pawtrail.gatewayserver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 토큰 검증에 쓰는 설정임
 * config 저장소의 gateway-server.yml 에서 내려옴
 *
 * @param publicKey 서명을 확인하는 공개키임
 *                  openssl 이 뱉은 PEM 을 가공하지 않고 그대로 받음
 * @param claim     토큰 안에서 값을 꺼낼 이름임
 *                  * 인증 서비스가 넣는 이름과 반드시 같아야 함
 *                    어긋나면 401 로만 나타나고 원인이 드러나지 않음
 */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(String publicKey, ClaimNames claim) {

    /**
     * @param accountId 사용자 식별자가 든 이름임, 기본은 표준 항목인 sub 임
     * @param role      권한이 든 이름임
     */
    public record ClaimNames(String accountId, String role) {
    }
}
