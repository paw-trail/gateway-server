package com.pawtrail.gatewayserver.filter;

import com.pawtrail.gatewayserver.response.ErrorResponseWriter;
import com.pawtrail.gatewayserver.response.GatewayErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

/**
 * 필터 밖에서 생긴 실패를 응답 규약에 맞춰 돌려주는 곳임
 *
 * 인증 실패는 필터가 직접 응답을 만들지만
 * 라우트를 찾지 못했거나 대상 서비스에 닿지 못한 경우는 필터까지 오지 않거나 그 뒤에 생김
 * 그대로 두면 프레임워크가 기본 형식으로 내려 주는데
 * 그러면 프론트엔드가 다뤄야 할 응답 형태가 두 가지가 됨
 *
 * 예외 처리기들은 순서대로 시도되며 앞선 것이 처리하면 뒤는 실행되지 않음
 * 프레임워크 기본 처리기가 -1 이므로 그보다 앞선 값을 지정했음
 *
 * * 프레임워크의 전용 인터페이스 대신 웹 계층의 표준 인터페이스를 구현함
 *   하는 일은 같으면서 판올림에 따라 자리가 바뀔 여지가 적기 때문임
 */
@Component
@Order(-2)
public class GatewayErrorHandler implements WebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorHandler.class);

    private final ErrorResponseWriter errorResponseWriter;

    public GatewayErrorHandler(ErrorResponseWriter errorResponseWriter) {
        this.errorResponseWriter = errorResponseWriter;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {

        // 응답이 이미 나가기 시작했으면 손대지 않고 넘김
        // 뒤쪽 서비스로 흘려보내던 중에 끊긴 경우가 여기에 해당함
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }

        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        GatewayErrorCode errorCode = resolve(ex);

        if (errorCode == GatewayErrorCode.INTERNAL_ERROR) {
            log.error("처리하지 못한 오류입니다. path={}", path, ex);
        } else {
            log.warn("요청을 처리하지 못했습니다. path={}, code={}, reason={}",
                    path, errorCode.getCode(), ex.getMessage());
        }

        return errorResponseWriter.write(exchange, errorCode);
    }

    /**
     * 예외를 응답 코드로 옮김
     *
     * * 예외의 종류가 아니라 상태 코드로 가르는 이유
     *   대상 서비스를 찾지 못했을 때 게이트웨이가 던지는 예외가 내부 클래스여서
     *   판올림마다 자리가 바뀔 수 있음
     *   그 예외도 상태 코드를 가진 부류라 아래 방식으로 함께 잡힘
     */
    private GatewayErrorCode resolve(Throwable ex) {
        if (!(ex instanceof ResponseStatusException statusException)) {
            return GatewayErrorCode.INTERNAL_ERROR;
        }

        HttpStatusCode status = statusException.getStatusCode();
        return switch (status.value()) {
            // 라우트 목록에 없는 경로임
            case 404 -> GatewayErrorCode.ROUTE_NOT_FOUND;
            // 라우트는 있으나 대상 서비스가 등록되어 있지 않거나 닿지 않음
            case 503 -> GatewayErrorCode.SERVICE_UNAVAILABLE;
            case 401 -> GatewayErrorCode.AUTHENTICATION_FAILED;
            case 403 -> GatewayErrorCode.ACCESS_DENIED;
            default -> GatewayErrorCode.INTERNAL_ERROR;
        };
    }
}
