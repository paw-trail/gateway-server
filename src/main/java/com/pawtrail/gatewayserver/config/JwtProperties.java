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
     * 값이 비어 있으면 기동을 막음
     *
     * 여기서 막지 않으면 기동은 정상이고 요청이 들어올 때가 되어서야 실패함
     * 게이트웨이는 그 대가가 큼 - 로그인 하나가 아니라 모든 요청이 무너짐
     *
     * 공개키가 비면 검증기를 만들다 키 파싱에 실패하는데,
     * 그 메시지만으로는 설정을 못 받았다는 것이 드러나지 않음
     */
    public JwtProperties {
        if (publicKey == null || publicKey.isBlank()) {
            throw new IllegalStateException(
                    "app.jwt.public-key 가 비어 있습니다. "
                            + "config 저장소의 gateway-server.yml 을 확인하십시오");
        }
        if (claim == null) {
            throw new IllegalStateException(
                    "app.jwt.claim 이 비어 있습니다. "
                            + "인증 서비스 설정과 같은 이름을 지정해야 합니다");
        }
    }

    /**
     * @param accountId 사용자 식별자가 든 이름임, 기본은 표준 항목인 sub 임
     * @param role      권한이 든 이름임
     * @param type      토큰의 종류가 든 이름임
     *                  * 액세스 토큰과 리프레시 토큰은 담는 내용이 같고 수명만 다름
     *                    이 값이 없으면 리프레시 토큰을 액세스 쿠키에 넣어 오래 쓸 수 있고,
     *                    그 경로는 갱신을 거치지 않아 토큰 폐기도 재사용 탐지도 지나침
     */
    public record ClaimNames(String accountId, String role, String type) {

        /**
         * 세 이름이 비어 있으면 기동을 막음
         *
         * 이름이 null 이면 토큰에서 값을 꺼내는 호출이 그대로 터져 모든 요청이 500 이 됨
         * 빈 문자열은 더 조용함 - 값을 못 찾아 401 만 나가고 원인이 드러나지 않음
         */
        public ClaimNames {
            if (accountId == null || accountId.isBlank()) {
                throw new IllegalStateException(
                        "app.jwt.claim.account-id 가 비어 있습니다. "
                                + "auth-service 의 app.jwt.claim.account-id 와 같아야 합니다");
            }
            if (role == null || role.isBlank()) {
                throw new IllegalStateException(
                        "app.jwt.claim.role 이 비어 있습니다. "
                                + "auth-service 의 app.jwt.claim.role 과 같아야 합니다");
            }
            if (type == null || type.isBlank()) {
                throw new IllegalStateException(
                        "app.jwt.claim.type 이 비어 있습니다. "
                                + "auth-service 의 app.jwt.claim.type 과 같아야 합니다");
            }
        }
    }
}
