package com.pawtrail.gatewayserver.response;

import org.springframework.http.HttpStatus;

/**
 * 게이트웨이가 직접 만드는 실패 응답의 코드임
 *
 * 앞의 둘은 공통 모듈의 CommonErrorCode 와 같은 문자열임
 * 프론트엔드는 받은 응답이 게이트웨이에서 온 것인지 도메인 서비스에서 온 것인지
 * 미리 알 수 없으므로, 같은 상황에는 같은 코드가 와야 한 가지로만 처리할 수 있음
 *
 * 뒤의 둘은 이곳에서만 나옴
 * 라우트를 찾지 못하거나 대상 서비스에 닿지 못한 것은 도메인 서비스가 낼 수 없는 응답임
 *
 * * 반대로 이 둘을 공통 모듈에 넣지는 않음
 *   도메인 서비스가 쓸 일이 없는 코드를 공통에 두면
 *   "공통에는 전 서비스가 쓰는 것만 둔다" 는 기준이 무너짐
 */
public enum GatewayErrorCode {

    // 쿠키가 없거나, 서명이 맞지 않거나, 만료된 경우임
    AUTHENTICATION_FAILED(HttpStatus.UNAUTHORIZED, "인증에 실패하였습니다."),

    // 관리자 경로인데 권한이 없는 경우임
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),

    // 라우트 목록에 없는 경로임
    // 이름 자체가 출처를 드러내므로 접두사를 따로 붙이지 않음
    // 도메인 서비스에는 라우트라는 개념이 없음
    ROUTE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청하신 경로를 찾을 수 없습니다."),

    // 라우트는 있으나 대상 서비스가 떠 있지 않은 경우임
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "일시적으로 서비스를 이용할 수 없습니다."),

    // 위 넷에 해당하지 않는 것임
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부에 에러가 발생하였습니다.");

    private final HttpStatus httpStatus;
    private final String message;

    GatewayErrorCode(HttpStatus httpStatus, String message) {
        this.httpStatus = httpStatus;
        this.message = message;
    }

    public HttpStatus getHttpStatus() {
        return this.httpStatus;
    }

    // 상수 이름이 곧 응답의 code 이자 API 계약임
    // 이름을 바꾸면 프론트엔드의 분기가 조용히 어긋나며 컴파일러가 잡아주지 않음
    public String getCode() {
        return this.name();
    }

    public String getMessage() {
        return this.message;
    }
}
