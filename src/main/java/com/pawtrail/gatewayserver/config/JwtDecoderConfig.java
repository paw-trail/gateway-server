package com.pawtrail.gatewayserver.config;

import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

/**
 * 토큰 검증기를 만드는 곳임
 *
 * 검증을 직접 구현하지 않는 이유는 확인해야 할 것이 서명만이 아니기 때문임
 * 만료 시각과 유효 시작 시각, 시계 오차, 그리고 알고리즘 혼동 공격까지 함께 다뤄야 함
 *
 * * 알고리즘 혼동은 실제로 있는 공격 유형임
 *   토큰 머리에 적힌 알고리즘을 그대로 믿으면
 *   공격자가 그것을 대칭키 방식으로 바꾸고 공개키를 비밀키 삼아 서명을 만들 수 있음
 *   공개키는 공개되어 있으므로 누구나 할 수 있음
 *   아래처럼 공개키로 만들면 RS256 으로 고정되어 이 경로가 막힘
 *
 * 이 서비스가 우리 인증의 유일한 검증 지점이라는 점이 이 선택의 근거임
 * 뒤쪽 서비스들은 토큰을 아예 보지 않으므로 여기가 뚫리면 두 번째 방어선이 없음
 */
@Configuration
@EnableConfigurationProperties({JwtProperties.class, GatewayAuthProperties.class})
public class JwtDecoderConfig {

    @Bean
    public ReactiveJwtDecoder jwtDecoder(JwtProperties properties) {
        return NimbusReactiveJwtDecoder
                .withPublicKey(toPublicKey(properties.publicKey()))
                .build();
    }

    /**
     * PEM 문자열을 공개키 객체로 바꿈
     *
     * 자바 표준 기능만 씀
     * 라이브러리가 제공하는 변환기를 쓸 수도 있으나 그 클래스의 위치가 판올림마다 바뀌어 온 편이라
     * 오래 그대로인 표준 쪽을 택했음
     * 하는 일은 머리와 꼬리 줄을 떼고 공백을 지운 뒤 되돌리는 것뿐임
     *
     * * 값이 잘못되면 기동할 때 예외가 나므로 조용히 지나가지 않음
     *   설정 파일에서 여러 줄 표기의 들여쓰기가 어긋나면 이 자리에서 드러남
     */
    private RSAPublicKey toPublicKey(String pem) {
        if (pem == null || pem.isBlank()) {
            throw new IllegalStateException(
                    "app.jwt.public-key 가 비어 있습니다. config 저장소의 gateway-server.yml 을 확인하십시오");
        }
        try {
            String body = pem
                    .replaceAll("-----[A-Z ]+-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(body);
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException(
                    "app.jwt.public-key 를 공개키로 읽지 못했습니다. 설정 파일의 들여쓰기와 값을 확인하십시오", e);
        }
    }
}
