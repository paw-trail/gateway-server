package com.pawtrail.gatewayserver.response;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실패 응답을 직접 써 내려 주는 곳임
 *
 * 인증 필터와 예외 처리기 양쪽에서 씀
 * 응답을 만드는 자리가 둘로 갈리면 한쪽만 고쳤을 때 형태가 어긋나므로 한곳에 모았음
 *
 * 이 서비스는 컨트롤러가 없어 응답 본문을 스프링이 대신 써 주지 않음
 * 그래서 상태 코드와 헤더, 본문을 직접 지정함
 */
@Component
public class ErrorResponseWriter {

    private final JsonMapper jsonMapper;
    private final Tracer tracer;

    public ErrorResponseWriter(JsonMapper jsonMapper, Tracer tracer) {
        this.jsonMapper = jsonMapper;
        this.tracer = tracer;
    }

    public Mono<Void> write(ServerWebExchange exchange, GatewayErrorCode errorCode) {
        ServerHttpResponse response = exchange.getResponse();

        // 이미 응답이 나가기 시작했으면 손대지 않음
        // 여기서 다시 쓰면 본문이 두 번 실려 클라이언트가 파싱에 실패함
        if (response.isCommitted()) {
            return Mono.empty();
        }

        response.setStatusCode(errorCode.getHttpStatus());

        // JSON 은 규약상 UTF-8 이므로 문자셋을 따로 붙이지 않고 바이트만 UTF-8 로 씀
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        GatewayApiResponse body = GatewayApiResponse.of(errorCode, currentTraceId());
        byte[] bytes = jsonMapper.writeValueAsBytes(body);

        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    /**
     * 지금 요청의 추적 식별자를 꺼냄
     *
     * 이 서비스가 요청이 처음 들어오는 자리이므로 여기에서 추적이 시작됨
     * 사용자가 문제를 알려 왔을 때 이 값으로 로그와 트레이스를 찾음
     *
     * * null 검사는 값이 없다는 판정이 아니라 다음 줄에서 터지지 않게 하려는 것임
     *   추적이 꺼져 있거나 스팬 밖이면 null 이 되는데 그래도 응답은 정상으로 나감
     */
    private String currentTraceId() {
        Span span = tracer.currentSpan();
        return span != null ? span.context().traceId() : null;
    }
}
