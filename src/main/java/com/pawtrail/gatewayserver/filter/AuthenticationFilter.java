package com.pawtrail.gatewayserver.filter;

import com.pawtrail.gatewayserver.config.GatewayAuthProperties;
import com.pawtrail.gatewayserver.config.JwtProperties;
import com.pawtrail.gatewayserver.response.ErrorResponseWriter;
import com.pawtrail.gatewayserver.response.GatewayErrorCode;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpCookie;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

/**
 * 인증을 처리하는 필터임
 *
 * 쿠키에 담긴 토큰을 확인하고 그 안의 사용자 정보를 헤더로 바꿔 뒤쪽 서비스에 넘김
 * 뒤쪽 서비스들은 토큰을 다루지 않고 이 헤더만 믿음
 *
 * 지나가는 순서는 아래와 같으며 각 단계의 이유는 해당 위치에 적었음
 *   들어온 인증 헤더 제거 → 열어 둔 경로인가 → 토큰 꺼내기
 *   → 서명 확인 → 토큰 종류 확인 → 사용자 정보 꺼내기 → 관리자 권한 → 헤더 주입
 */
@Component
public class AuthenticationFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationFilter.class);

    // 뒤쪽 서비스의 공통 모듈이 읽는 이름과 정확히 같아야 함
    // 공통 모듈을 의존하지 않으므로 같은 문자열이 양쪽에 따로 존재하며
    // 어긋나면 뒤쪽 서비스가 인증되지 않은 요청으로 보아 401 을 냄
    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String USER_ROLE_HEADER = "X-User-Role";

    private static final String ACCESS_TOKEN_COOKIE = "access_token";
    private static final String ADMIN_PATH_PREFIX = "/api/v1/admin/";
    private static final String ADMIN_ROLE = "ADMIN";

    // 이 게이트웨이가 받아들이는 토큰의 종류임
    //
    // 인증 서비스가 토큰에 넣는 값과 같은 문자열이어야 함
    // 그쪽은 열거형으로 두고 있으나 공통 모듈을 쓰지 않아 함께 쓸 수 없음
    // 인증 예외 경로 목록이나 토픽 이름과 같은 성격의 중복이며 감수한 것임
    //
    // 설정으로 빼지 않은 것은 의도임
    // claim 의 이름은 환경마다 달라질 수 있어 설정이지만
    // 그 안에 담기는 값은 두 서비스 사이의 규약이라 코드에 두는 것이 맞음
    private static final String ACCESS_TOKEN_TYPE = "access";

    private final ReactiveJwtDecoder jwtDecoder;
    private final ErrorResponseWriter errorResponseWriter;
    private final JwtProperties jwtProperties;
    private final List<PathPattern> permitAllPatterns;

    public AuthenticationFilter(ReactiveJwtDecoder jwtDecoder,
                                ErrorResponseWriter errorResponseWriter,
                                JwtProperties jwtProperties,
                                GatewayAuthProperties gatewayAuthProperties) {
        this.jwtDecoder = jwtDecoder;
        this.errorResponseWriter = errorResponseWriter;
        this.jwtProperties = jwtProperties;

        // 패턴 해석을 기동할 때 한 번만 함
        // 요청마다 문자열을 다시 읽으면 모든 요청이 그 비용을 나눠 짐
        PathPatternParser parser = new PathPatternParser();
        this.permitAllPatterns = gatewayAuthProperties.permitAll().stream()
                .map(parser::parse)
                .toList();

        log.info("인증 예외 경로 {}개를 읽었습니다: {}",
                this.permitAllPatterns.size(), gatewayAuthProperties.permitAll());
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {

        // 들어온 인증 헤더를 먼저 지움
        //
        // * 이 단계가 다른 무엇보다 먼저여야 함
        //   뒤쪽 서비스는 이 게이트웨이가 넣어 준 헤더를 믿도록 만들어져 있으므로
        //   바깥에서 들어온 같은 이름의 헤더가 남아 있으면 그대로 신뢰됨
        //   아래처럼 보내면 남의 계정으로 행세할 수 있음
        //     POST /api/v1/auth/login
        //          X-User-Id: <남의 식별자>
        //          X-User-Role: ADMIN
        //
        // * 열어 둔 경로라고 건너뛰면 안 됨
        //   그 경로도 결국 뒤쪽 서비스로 가기 때문임
        //   그래서 다음 단계보다 앞에 두었으며 조건 없이 실행함
        //
        // * 이 실패는 오류가 아니라 정상 응답으로 나타나므로 눈에 띄지 않음
        ServerWebExchange cleaned = exchange.mutate()
                .request(request -> request.headers(headers -> {
                    headers.remove(USER_ID_HEADER);
                    headers.remove(USER_ROLE_HEADER);
                }))
                .build();

        ServerHttpRequest request = cleaned.getRequest();
        String path = request.getPath().pathWithinApplication().value();

        // 인증 없이 열어 둔 경로인지 봄
        // 로그인처럼 토큰을 받기 전에 불러야 하는 것들임
        if (isPermitAll(request)) {
            return chain.filter(cleaned);
        }

        // 쿠키에서 토큰을 꺼냄
        // 헤더가 아니라 쿠키인 이유는 브라우저 스크립트가 토큰을 읽지 못하게 하기 위함임
        HttpCookie cookie = request.getCookies().getFirst(ACCESS_TOKEN_COOKIE);
        if (cookie == null || cookie.getValue().isBlank()) {
            log.debug("토큰 쿠키가 없습니다. path={}", path);
            return errorResponseWriter.write(cleaned, GatewayErrorCode.AUTHENTICATION_FAILED);
        }

        // 서명과 만료를 확인한 뒤 사용자 정보를 꺼내 헤더로 넣음
        return jwtDecoder.decode(cookie.getValue())
                .flatMap(jwt -> authenticate(cleaned, chain, jwt, path))
                .onErrorResume(error -> {
                    // 서명 불일치 · 만료 · 형식 오류가 모두 여기로 옴
                    // 어느 쪽이든 클라이언트에게는 같은 응답을 주되 원인은 로그에 남김
                    // 응답에 사유를 적으면 토큰을 맞춰 보는 데 단서가 됨
                    log.debug("토큰 검증에 실패했습니다. path={}, reason={}", path, error.getMessage());
                    return errorResponseWriter.write(cleaned, GatewayErrorCode.AUTHENTICATION_FAILED);
                });
    }

    private Mono<Void> authenticate(ServerWebExchange exchange, GatewayFilterChain chain,
                                    Jwt jwt, String path) {

        // 토큰의 종류를 먼저 봄
        //
        // * 이 검사가 없으면 리프레시 토큰을 이 쿠키에 넣어 쓸 수 있음
        //   두 토큰은 담는 내용이 같고 수명만 달라 서명과 만료만으로는 구분이 안 됨
        //   그러면 갱신 경로를 한 번도 거치지 않고 14일간 쓰이며,
        //   그 사이 토큰 교체도 재사용 탐지도 계정의 폐기 기준 시각도 지나침
        //   비밀번호를 바꿔도 그 경로는 끊기지 않음
        //
        // * 종류가 없는 토큰도 여기서 걸림
        //   인증 서비스가 이 값을 넣기 전에 발급된 토큰이며 더는 받지 않음
        //   액세스 토큰의 수명이 짧아 그런 토큰은 곧 사라짐
        String tokenType = jwt.getClaimAsString(jwtProperties.claim().type());

        if (!ACCESS_TOKEN_TYPE.equals(tokenType)) {
            log.debug("액세스 토큰이 아닙니다. path={}, type={}", path, tokenType);
            return errorResponseWriter.write(exchange, GatewayErrorCode.AUTHENTICATION_FAILED);
        }

        String accountId = jwt.getClaimAsString(jwtProperties.claim().accountId());
        String role = jwt.getClaimAsString(jwtProperties.claim().role());

        // 서명은 맞는데 안에 들어 있어야 할 값이 없는 경우임
        // 인증 서비스와 이름이 어긋나면 여기로 떨어짐
        if (accountId == null || role == null) {
            log.warn("토큰에 필요한 값이 없습니다. 인증 서비스가 넣는 이름과 설정이 같은지 확인하십시오. "
                            + "기대한 이름: {} / {}",
                    jwtProperties.claim().accountId(), jwtProperties.claim().role());
            return errorResponseWriter.write(exchange, GatewayErrorCode.AUTHENTICATION_FAILED);
        }

        // 관리자 경로는 여기서 먼저 막음
        //
        // 뒤쪽 서비스의 공통 모듈도 같은 경로를 막고 있어 규칙이 두 곳에 있음
        // 그럼에도 여기에 두는 이유는 공통 모듈의 보호에 예외가 생기기 때문임
        // 서비스가 자기 보안 설정을 정의하면 공통 설정이 통째로 물러나며
        // 인증 서비스가 실제로 그렇게 할 예정임
        //
        // 규칙이 겹치는 것은 해롭지 않음
        // 어느 한쪽만 살아 있어도 막히므로 더 엄격한 쪽이 이김
        if (path.startsWith(ADMIN_PATH_PREFIX) && !ADMIN_ROLE.equals(role)) {
            log.warn("관리자 경로에 권한 없이 접근했습니다. path={}, role={}", path, role);
            return errorResponseWriter.write(exchange, GatewayErrorCode.ACCESS_DENIED);
        }

        // 뒤쪽 서비스가 읽을 수 있게 헤더로 옮김
        //
        // * 권한 이름에 접두사를 붙이지 않음
        //   뒤쪽 서비스의 공통 필터가 ROLE_ 을 붙여 권한 객체를 만들기 때문임
        //   여기서 미리 붙이면 두 번 붙어 권한 검사가 어긋남
        //
        // * 값의 형식이 어긋나면 뒤쪽 서비스가 조용히 인증 없이 통과시킨 뒤 401 을 냄
        //   식별자는 UUID 형식, 권한은 USER 또는 ADMIN 이어야 함
        ServerWebExchange authenticated = exchange.mutate()
                .request(request -> request.headers(headers -> {
                    headers.set(USER_ID_HEADER, accountId);
                    headers.set(USER_ROLE_HEADER, role);
                }))
                .build();

        return chain.filter(authenticated);
    }

    private boolean isPermitAll(ServerHttpRequest request) {
        var pathContainer = request.getPath().pathWithinApplication();
        return permitAllPatterns.stream().anyMatch(pattern -> pattern.matches(pathContainer));
    }

    /**
     * 라우팅보다 먼저 실행되도록 앞쪽 순서를 지정함
     *
     * 게이트웨이가 기본으로 두는 필터들은 만 단위의 값을 쓰므로 음수면 그보다 앞섬
     * 인증에 실패한 요청이 대상 서비스로 나가기 전에 걸러져야 함
     */
    @Override
    public int getOrder() {
        return -1;
    }
}
