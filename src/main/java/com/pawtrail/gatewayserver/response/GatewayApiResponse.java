package com.pawtrail.gatewayserver.response;

/**
 * 게이트웨이의 응답 형태임
 *
 * 공통 모듈의 CommonApiResponse 와 필드가 같음
 * 공통 모듈을 의존하지 않으므로 클래스를 가져다 쓰지 못하고 같은 모양을 여기에 다시 정의했음
 *
 * * 형태를 맞추는 이유는 프론트엔드가 응답의 출처를 구분할 수 없기 때문임
 *   같은 401 이 이곳에서 올 수도 도메인 서비스에서 올 수도 있는데
 *   형태가 다르면 두 가지를 모두 다뤄야 하고
 *   "code 가 SUCCESS 가 아니면 실패" 라는 규약이 깨짐
 *
 * 이 서비스는 실패 응답만 만들므로 data 는 언제나 null 임
 * 그래도 필드를 두는 것은 형태를 맞추기 위함임
 */
public record GatewayApiResponse(
        String code,
        String message,
        Object data,
        String traceId
) {

    public static GatewayApiResponse of(GatewayErrorCode errorCode, String traceId) {
        return new GatewayApiResponse(errorCode.getCode(), errorCode.getMessage(), null, traceId);
    }
}
