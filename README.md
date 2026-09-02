# gateway-server

**함께하개의 API 게이트웨이입니다.** 브라우저에서 오는 모든 요청이 여기를 거칩니다.

하는 일은 **셋**입니다.

```
브라우저  ──▶  게이트웨이  ──▶  도메인 서비스 14개
                   │                    │
                   │                    └──▶  X-User-Id · X-User-Role 헤더만 믿음
                   │                          토큰을 아예 안 봄
                   │
                   ├──▶  쿠키의 JWT 를 공개키로 검증
                   ├──▶  어느 서비스로 보낼지 라우트 표에서 찾음
                   └──▶  유레카에게 그 서비스의 주소를 물어봄

                     [ config 저장소 ]  ──▶  라우트 19개 · 공개키 · 인증 예외 9줄
                                             기동할 때 설정 서버가 내려 줌
```

<br><br>

---

## 0. 이 서비스가 하는 일

**게이트웨이가 없으면 이렇게 됩니다.**

| | 게이트웨이가 있으면 | 없으면 |
|---|---|---|
| 브라우저가 부르는 주소 | `localhost:8080` 하나 | 서비스마다 다른 포트 14개 |
| 토큰 검증 | 여기 한 곳 | **서비스 14개가 각자** |
| 서비스 주소가 바뀌면 | 유레카가 알아서 | 프론트를 고쳐야 함 |
| 관리자 경로 보호 | 여기서 먼저 막음 | 한 곳만 빠뜨려도 열림 |

---

**숫자로 보면 이렇습니다.**

| | 값 | 어디에 |
|---|---|---|
| 자바 파일 | **9개** | [6장](#6-코드-구조) |
| 라우트 | 19개 | config 저장소 — [3-1](#3-1-라우트-19개) |
| 인증 예외 경로 | 9줄 | config 저장소 — [3-4](#3-4-인증-없이-여는-경로) |
| 필터 단계 | 8단계 | [2장](#2-요청이-지나가는-길) |
| 게이트웨이가 직접 내는 응답 | 5가지 | [4장](#4-응답) |
| 공통 모듈 | **안 씀** | [6-3](#6-3-공통-모듈을-쓰지-않습니다) |
| DB · Redis · Kafka | **안 씀** | 상태를 하나도 안 가집니다 |

---

**auth 와 하는 일이 갈립니다.**

```
auth        토큰을 만듦          로그인 · 갱신 · 소셜
              │
              │  개인키로 서명
              ▼
          [ JWT 토큰 ]
              │
              │  공개키로 검증
              ▼
게이트웨이    토큰을 확인함        모든 요청마다
```

| | auth | 게이트웨이 |
|---|---|---|
| 키 | 개인키 (환경변수) | 공개키 (config 저장소) |
| 언제 | 로그인할 때만 | **모든 요청마다** |
| 하는 일 | 발급 · 폐기 · 계정 관리 | 검증 · 라우팅 |
| 상태 | DB · Redis 를 가짐 | **아무것도 안 가짐** |

<br><br>

---

### 먼저 알아 두면 좋은 것 4가지

**아래 네 단어를 모르면 이 문서가 안 읽힙니다.** 한 문단씩만 보고 갑니다.

---

**① 서비스가 왜 14개로 나뉘어 있나 — MSA**

```
하나의 큰 프로그램 (모놀리식)          작은 프로그램 여럿 (MSA · 우리 방식)

  로그인 · 장소 · 반려동물 · 판정        auth    place    pet    verdict  ...
  전부 한 덩어리                          │       │       │       │
        │                               DB      DB      DB     (없음)
        ▼
  한 번에 뜨고 한 번에 죽음               각자 따로 뜨고 각자 자기 DB 를 가짐
  한 군데 고쳐도 전체를 다시 배포           한 서비스가 죽어도 나머지는 돎
                                        서비스마다 따로 배포할 수 있음
```

**대신 "요청을 누가 받아 어디로 보내나" 가 문제가 됩니다.** 그 자리가 게이트웨이입니다.

---

**② 게이트웨이가 서비스를 어떻게 찾나 — 유레카**

서비스 주소를 게이트웨이에 적어 두면 **서비스가 포트를 바꾸거나 늘어날 때마다
게이트웨이를 고쳐야 합니다.** 그래서 **주소 장부(유레카)** 를 따로 둡니다.

```
① place-service 가 기동하며 유레카에 등록     "나 172.18.0.7:8084 에 있어"
        │
        ▼
② 게이트웨이가 유레카에 물어봄               "place-service 어디 있어?"
        │
        ▼
③ 유레카가 답함                             "172.18.0.7:8084"
        │
        ▼
④ 게이트웨이가 그 주소로 요청을 보냄
```

**게이트웨이 설정에는 주소가 아니라 이름만 있습니다.** `lb://place-service` 처럼.

---

**③ 로그인했다는 것을 어떻게 아나 — JWT 와 쿠키**

```
로그인 성공
    │
    └──▶  auth 가 토큰(JWT)을 만들어 쿠키에 담아 줌
                │
                └──▶  브라우저가 그 뒤 모든 요청에 쿠키를 자동으로 실어 보냄
                            │
                            └──▶  게이트웨이가 쿠키의 토큰을 검증
                                    "이 사람 누구고 무슨 권한인지" 를 헤더로 붙여 뒤로 넘김
```

**JWT** 는 사용자 정보를 담고 **서명**한 문자열입니다. 서명은 **auth 만 가진 개인키**로
만들고, 게이트웨이는 **공개키**로 확인만 합니다. 고치면 서명이 안 맞아 거부됩니다.
자세한 것은 `auth-service` README 2장에 있습니다.

---

**④ 필터가 뭔가**

**요청이 목적지로 가기 전에 반드시 거치는 검문소**입니다. 이 서비스의 자바 코드 대부분이
필터 하나(`AuthenticationFilter`)이고, [2장](#2-요청이-지나가는-길)이 그것을 설명합니다.

<br><br>

---

### 이 문서를 읽는 순서

| 지금 하려는 일 | 볼 곳 |
|---|---|
| 일단 띄워보고 싶다 | [1장](#1-로컬에서-띄우기) |
| 요청이 어떻게 처리되는지 모르겠다 | [2장](#2-요청이-지나가는-길) |
| 새 서비스의 라우트를 열어야 한다 | [3-5](#3-5-라우트를-새로-여는-법) |
| 401 · 403 · 404 · 503 이 나온다 | [4장](#4-응답) → [9장](#9-막히기-쉬운-자리) |
| 설정값이 어디서 오는지 | [5장](#5-설정값) |
| 코드를 고치려는데 어느 파일인지 | [6장](#6-코드-구조) |
| 공개키를 바꿔야 한다 | [7-2](#7-2-키-페어를-교체할-때) |
| "왜 이렇게 만들었지" | [8장](#8-왜-이렇게-만들었나) |
| 모르는 말이 나온다 | [11장](#11-용어) |

> **공통 규칙은 이 문서에 없습니다.** 설정 4계층 · Docker 환경 · 이미지 굽는 절차는
> [`service-template` README](https://github.com/paw-trail/service-template) 와
> [`infra` README](https://github.com/paw-trail/infra) 에 있습니다.

---

**도메인 서비스와 다른 점 넷입니다.**

```
1  WebFlux 임             도메인 서비스는 WebMVC (Tomcat)
                          게이트웨이는 Netty 로 돌고 리액티브 코드를 씀

2  공통 모듈을 안 씀        플랫폼 3개 공통 규칙
                          응답 형태 · 헤더 이름을 자기 저장소에 따로 적음

3  4계층 구조가 아님        config · filter · response 세 폴더뿐
                          도메인이 없으므로 도메인 계층도 없음

4  상태가 없음             DB · Redis · Kafka 를 하나도 안 씀
                          그래서 인스턴스를 늘려도 아무 조정이 필요 없음
```

<br><br>

---

## 1. 로컬에서 띄우기

**준비물이 거의 없습니다.** DB 도 Redis 도 안 쓰고 환경변수도 사실상 없습니다.

<br><br>

---

### 1-1. 필요한 것

| | 필요? | 왜 |
|---|---|---|
| `config-server` | **필수** | 라우트·공개키가 전부 거기서 옵니다 |
| `eureka-server` | **필수** | 없으면 목적지 주소를 못 찾아 503 |
| PostgreSQL · Redis · Kafka | 불필요 | 게이트웨이는 상태를 안 가집니다 |
| 환경변수 | **없음** | `CONFIG_HOST` 는 기본값 `localhost` 로 충분 |
| RS256 개인키 | 불필요 | **공개키만 쓰고 그것은 config 저장소에 있습니다** |

```bash
cd <infra 경로>
docker compose up -d        # config-server · eureka-server 가 포함된 조합
docker compose ps           # 둘이 (healthy) 인지
```

> 기본 조합 `infra,platform,db,tools` 에 `platform` 이 들어 있어 그대로 뜹니다.
> **게이트웨이를 컨테이너로 띄우고 있다면 먼저 멈춥니다** — 8080 이 겹칩니다.
>
> ```bash
> docker compose stop gateway-server
> ```

<br><br>

---

### 1-2. 띄우고 확인하기

```
① docker compose up -d                config-server · eureka-server 가 먼저 떠 있어야 함
        │
        ▼
② GatewayServerApplication 실행        IntelliJ
        │
        ├──▶  config-server 에서 gateway-server.yml 을 받음
        │       라우트 19개 · 공개키 · 인증 예외 9줄
        ├──▶  공개키를 RSAPublicKey 로 변환해 JwtDecoder 를 만듦
        └──▶  유레카에 GATEWAY-SERVER 로 등록
        │
        ▼
③ 기동 로그                            Netty started on port 8080
        │                              Exposing 5 endpoints beneath base path '/actuator'
        ▼                                        ▲
④ curl :8080/actuator/gateway/routes            └── 4 면 gateway 엔드포인트가 안 켜진 것
        │
        ├── 19개가 나옴  ──▶  설정을 받았음
        └── 빈 배열      ──▶  config 를 못 받았음  (포트로는 판별이 안 됨)
```

---

**④가 이 서비스의 유일한 판별법입니다.**

다른 서비스는 *"설정을 못 받으면 포트가 8080 으로 뜬다"* 로 알 수 있는데,
**게이트웨이는 정상 포트가 8080 이라 그 방법이 안 통합니다.**

```
eureka-server     정상 8761  →  8080 이면 config 미수신
config-server     정상 8888  →  8080 이면 config 미수신
gateway-server    정상 8080  →  ⛔ 판별 불가
                                 /actuator/gateway/routes 가 비어 있는 것으로 가려냄
```

**macOS**

```bash
curl http://localhost:8080/actuator/gateway/routes
```

**Windows (PowerShell)**

```powershell
curl.exe http://localhost:8080/actuator/gateway/routes
```

---

**정상이면 이런 게 19개 나옵니다.**

```json
[
  {
    "predicate": "Paths: [/api/v1/auth/**], match trailing slash: true",
    "route_id": "auth-service",
    "filters": [],
    "uri": "lb://auth-service",
    "order": 0
  },
  ...
]
```

> **`order` 가 전부 `0` 인 것이 정상입니다.** 우리 라우트는 서로 겹치지 않게 짜여 있어
> 순서에 기대지 않습니다. [3-2](#3-2-placeid-겹침--여기가-제일-까다롭습니다) 참고.

<br><br>

---

### 1-3. 실제로 요청을 보내 봅니다

**게이트웨이만 확인하려면 auth 를 띄우지 않아도 됩니다.**
인증 필터가 라우팅보다 먼저 돌기 때문입니다.

| 보낼 것 | 기대 응답 | 무엇이 확인되나 |
|---|---|---|
| 쿠키 없이 `GET /api/v1/pets` | 401 `AUTHENTICATION_FAILED` | ⓒ 가 동작 |
| `POST /api/v1/auth/login` | 503 `SERVICE_UNAVAILABLE` | **ⓑ 가 동작** — 필터를 지나 라우팅까지 감 |
| `GET /api/v1/nonexistent` | 404 `ROUTE_NOT_FOUND` | 예외 처리기가 동작 |
| `X-User-Id` 를 직접 붙여 보냄 | 401 `AUTHENTICATION_FAILED` | **ⓐ 가 동작** — 위조 헤더를 지움 |

**macOS**

```bash
curl -i http://localhost:8080/api/v1/pets
curl -i -X POST http://localhost:8080/api/v1/auth/login
curl -i http://localhost:8080/api/v1/nonexistent
curl -i -H "X-User-Id: 01a00000-0000-7000-8000-000000000000" -H "X-User-Role: ADMIN" \
     http://localhost:8080/api/v1/pets
```

**Windows (PowerShell)**

```powershell
curl.exe -i http://localhost:8080/api/v1/pets
curl.exe -i -X POST http://localhost:8080/api/v1/auth/login
curl.exe -i http://localhost:8080/api/v1/nonexistent
curl.exe -i -H "X-User-Id: 01a00000-0000-7000-8000-000000000000" -H "X-User-Role: ADMIN" `
     http://localhost:8080/api/v1/pets
```

---

**두 번째가 401 이 아니라 503 인 것이 정답입니다.**

```
401 이면   ⛔ 인증 예외 목록에 login 이 빠진 것
503 이면   ✅ 필터를 통과해 라우팅까지 갔고, auth 가 안 떠 있을 뿐
```

**네 번째가 이 서비스의 핵심 검증입니다.** 위조 헤더를 붙였는데 401 이 난다는 것은
**게이트웨이가 그 헤더를 지우고 자기 검증을 했다는 뜻**입니다.

---

**`traceId` 가 `null` 인 것은 정상입니다.**

`observability` 프로파일을 안 켰으면 Zipkin 이 없어 스팬이 안 만들어집니다.
응답은 정상으로 나갑니다.

<br><br>

---

## 2. 요청이 지나가는 길

**이 장이 이 서비스의 전부입니다.** 자바 파일 9개가 하는 일이 아래 그림 하나입니다.

<br><br>

---

### 2-1. 필터 8단계

```
GET /api/v1/pets     Cookie: access_token=eyJ...
        │
        ▼
┌ AuthenticationFilter ─────────────────────────────────────────────────
│
│  ⓐ 들어온 X-User-Id · X-User-Role 헤더를 지움
│         조건 없이 · 무엇보다 먼저
│         └─ 안 지우면 브라우저가 직접 붙인 값이 그대로 신뢰됨
│         │
│         ▼
│  ⓑ 인증 없이 열어 둔 경로인가            ── 예 ──▶  그대로 통과 (토큰을 안 봄)
│         │ 아니오
│         ▼
│  ⓒ access_token 쿠키가 있나             ── 없음 ─▶  401 AUTHENTICATION_FAILED
│         │ 있음
│         ▼
│  ⓓ 공개키로 서명·만료를 확인             ── 실패 ─▶  401 AUTHENTICATION_FAILED
│         │ 통과                                      (서명 오류·만료·형식 오류가 전부 여기)
│         ▼
│  ⓔ typ claim 이 access 인가             ── 아님 ─▶  401 AUTHENTICATION_FAILED
│         │ 맞음                                      (리프레시 토큰을 넣은 경우)
│         ▼
│  ⓕ sub · role claim 을 꺼냄             ── 없음 ─▶  401 AUTHENTICATION_FAILED
│         │
│         ▼
│  ⓖ /api/v1/admin/ 인데 role 이 ADMIN 이 아닌가  ── 예 ──▶  403 ACCESS_DENIED
│         │ 아니오
│         ▼
│  ⓗ X-User-Id · X-User-Role 을 붙여 다음으로
│
└───────────────────────────┬───────────────────────────────────────────
                            ▼
                     라우트 표에서 목적지를 찾음
                            │
                            ├── 없음 ──▶  404 ROUTE_NOT_FOUND
                            │
                            ▼
                     유레카에서 주소를 받아 전달
                            │
                            └── 서비스가 안 떠 있음 ──▶  503 SERVICE_UNAVAILABLE
```

---

**단계마다 무엇을 막는지입니다.**

| 단계 | 하는 일 | 안 하면 |
|---|---|---|
| ⓐ | 들어온 인증 헤더 제거 | **누구나 남의 계정으로 행세함** |
| ⓑ | 인증 예외 경로 통과 | 로그인·가입이 401 이 되어 아무도 못 들어옴 |
| ⓒ | 쿠키에서 토큰 꺼냄 | — |
| ⓓ | 서명·만료 검증 | 아무 문자열이나 토큰으로 통함 |
| ⓔ | `typ` 이 `access` 인지 | **리프레시 토큰을 14일간 쓸 수 있음** |
| ⓕ | `sub`·`role` 꺼냄 | 뒤쪽 서비스가 누구인지 모름 |
| ⓖ | 관리자 경로 권한 | 일반 사용자가 관리자 API 를 부름 |
| ⓗ | 헤더 주입 | 뒤쪽 서비스가 인증을 못 알아봄 |

<br><br>

---

### 2-2. 헤더 제거가 맨 앞이어야 하는 이유

**뒤쪽 서비스는 게이트웨이가 넣어 준 헤더를 그냥 믿습니다.**
그 신뢰가 성립하려면 **바깥에서 들어온 같은 이름의 헤더를 반드시 지워야 합니다.**

```
⛔ ⓐ 를 ⓑ 뒤에 두면

공격자  ──▶  POST /api/v1/auth/login          ← 인증 예외 경로
                 X-User-Id: <남의 계정 UUID>
                 X-User-Role: ADMIN
                     │
                     ├── ⓑ 예외 경로네, 통과!
                     │
                     ▼
              auth-service 도착
                 헤더가 그대로 살아 있음  →  common 필터가 그 값을 믿음
                 →  남의 계정으로 행세함


✅ ⓐ 를 맨 앞에 두면

공격자  ──▶  POST /api/v1/auth/login
                 X-User-Id: <남의 계정 UUID>     ← ⓐ 가 지움
                 X-User-Role: ADMIN              ← ⓐ 가 지움
                     │
                     ▼
              auth-service 도착
                 헤더가 없음  →  인증 없는 요청으로 처리됨


* 이 실패는 오류가 아니라 정상 응답으로 나타나므로 눈에 안 띔
```

> **인증 예외 경로라고 건너뛰면 안 됩니다.** 그 경로도 결국 뒤쪽 서비스로 갑니다.
> 그래서 ⓐ 는 **조건 없이** 실행되고 ⓑ 보다 앞에 있습니다.

---

**VPC 격리로는 이걸 못 막습니다.**

```
VPC · 보안그룹이 막는 것       바깥에서 도메인 서비스(8081 등)에 직접 접속
VPC 가 못 막는 것             게이트웨이를 정상 경로로 통과하는 위조 헤더
```

**둘은 다른 문제이고 각자 대책이 있어야 합니다.**

<br><br>

---

### 2-3. ⓔ 토큰 종류 검사

```
두 토큰은 담는 내용이 같고 수명만 다름

access_token   sub · role · typ=access  · exp 30분
refresh_token  sub · role · typ=refresh · exp 14일  + jti

        ⛔ typ 검사가 없으면

리프레시 토큰을 access_token 쿠키에 넣음
        │
        ├──▶  서명 통과 (같은 키로 서명됨)
        ├──▶  만료 통과 (14일이 남음)
        └──▶  통과!  →  14일간 모든 API 를 씀
                        갱신 경로를 한 번도 안 지나므로
                          로테이션 · 재사용 탐지 · tokens_valid_from 을 전부 우회
                          비밀번호를 바꿔도 안 끊김

        ✅ typ 검사가 있으면

                   typ=refresh  →  401
```

---

**`typ` 이 아예 없는 토큰도 거부합니다.**

```java
// 상수 쪽에서 equals 를 부르므로 null 이 자동으로 걸림
if (!ACCESS_TOKEN_TYPE.equals(tokenType)) { ... }
```

통과시키면 **공격자가 `typ` 없는 옛 토큰을 쓰면 그만**이라 검사를 넣은 의미가 없어집니다.

---

**claim 이름은 설정이고 값은 코드입니다.**

| | 어디에 | 왜 |
|---|---|---|
| claim 이름 `typ` | config `app.jwt.claim.type` | 환경마다 달라질 수 있음. **auth 와 같은 값이어야 함** |
| 값 `"access"` | 필터의 `private static final` | auth 와의 **규약**이지 설정이 아님 |

> 공통 모듈을 안 쓰므로 auth 의 `TokenType` enum 을 공유할 수 없습니다.
> `X-User-Id` 문자열과 같은 성격의 중복입니다.

<br><br>

---

### 2-4. ⓖ 관리자 경로 — 두 겹으로 막습니다

```
브라우저  ──▶  게이트웨이         path 가 /api/v1/admin/ 으로 시작하는데
                   │              role 이 ADMIN 이 아니면  403
                   │
                   ▼
              도메인 서비스        공통 모듈 보안 체인이 한 번 더
                                  .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
```

**게이트웨이가 먼저 막는 이유**는 auth 처럼 **자기 보안 체인을 정의한 서비스에서
공통 모듈의 보호가 물러나기 때문**입니다.

---

**어느 층이 막았는지는 `traceId` 로 갈립니다.**

| 응답 | `traceId` | 누가 |
|---|---|---|
| 403 `ACCESS_DENIED` | `null` | **게이트웨이** — 도메인 서비스까지 안 감 |
| 403 `ACCESS_DENIED` | 값 있음 | 도메인 서비스의 공통 모듈 |

---

**`role` 값에 접두사를 붙이지 않습니다.**

```
게이트웨이가 넣음    X-User-Role: ADMIN
공통 모듈이 만듦     "ROLE_" + role     →  ROLE_ADMIN
```

**게이트웨이가 `ROLE_ADMIN` 을 넣으면 `ROLE_ROLE_ADMIN` 이 되어** 권한이 안 맞습니다.

<br><br>

---

### 2-5. 필터를 거친 뒤 — 라우팅

```
X-User-Id 가 붙은 요청
        │
        ▼
Spring Cloud Gateway 가 routes 목록을 위에서부터 대조
        │
        ├── Path=/api/v1/auth/**              ✗
        ├── Path=/api/v1/pets/**,/api/v1/breeds  ✓ 맞음 — 여기서 멈춤
        │        │
        │        └──▶  uri: lb://pet-service
        │                     │
        │                     └──▶  유레카에게 pet-service 주소를 물어봄
        │                              │
        │                              ├── 있음  ──▶  그 주소로 전달
        │                              └── 없음  ──▶  503 SERVICE_UNAVAILABLE
        │
        └── 끝까지 안 맞음  ──▶  404 ROUTE_NOT_FOUND
```

> **처음 맞는 라우트에서 멈춥니다.** 그래서 경로가 넓은 라우트가 앞에 있으면
> 뒤엣것은 영영 안 걸립니다. [3-2](#3-2-placeid-겹침--여기가-제일-까다롭습니다) 참고.

---

**`lb://` 가 하는 일입니다.**

```
uri: lb://pet-service
      │      │
      │      └── 유레카에 등록된 이름 (= 그 서비스의 spring.application.name)
      │
      └── LoadBalancer 가 처리하라는 표시
             인스턴스가 여럿이면 번갈아 보냄
```

**주소를 직접 적지 않으므로 서비스가 어느 포트에 뜨든 게이트웨이는 몰라도 됩니다.**

<br><br>

---

## 3. 라우팅

**라우트는 이 저장소가 아니라 `paw-trail/config` 에 있습니다.**

```
paw-trail/config/gateway-server.yml
        │
        │  기동할 때 · /actuator/refresh 할 때
        ▼
게이트웨이가 읽어 라우트 표를 만듦
```

> **코드에 두지 않은 이유** — 도메인 서비스가 하나씩 완성될 때마다 라우트를 열어야 하는데,
> 코드에 있으면 그때마다 **이미지 재빌드 + 무중단 배포 한 사이클**을 돌게 됩니다.
> config 에 있으면 **push 와 refresh 로 끝납니다.**

<br><br>

---

### 3-1. 라우트 19개

```
서비스별 (12개)
  auth          /api/v1/auth/**
  user          /api/v1/users/**  /api/v1/favorites/**  /api/v1/visits/**  /api/v1/itineraries/**
  pet           /api/v1/pets/**   /api/v1/breeds
  place         /api/v1/places/{placeId}   /api/v1/places/{placeId}/documents
  verdict       /api/v1/places/{placeId}/verdict
  review        /api/v1/places/{placeId}/reviews   /api/v1/reviews/**
  policy        /api/v1/places/{placeId}/conflicts
  congestion    /api/v1/places/{placeId}/congestion
  search        /api/v1/search/**
  report        /api/v1/reports/**
  notification  /api/v1/notifications/**
  route         /api/v1/routes

관리자 (7개) — 두 번째 마디가 서비스를 결정함, 예외 없음
  auth    /api/v1/admin/accounts/**      pet      /api/v1/admin/pets/**
  place   /api/v1/admin/places/**        policy   /api/v1/admin/policies/**
  report  /api/v1/admin/reports/**       verdict  /api/v1/admin/verdicts/**
  search  /api/v1/admin/search/**

라우트를 만들지 않는 것
  /internal/**     서비스끼리만 부름. 게이트웨이가 라우팅하지 않아 바깥에서 닿지 않음
  config-server    설정 원본이 통째로 나가는 곳
  ingest · extract 공개 API 가 0개인 배치 서비스
```

> **관리자 경로 규칙에 예외가 없습니다.** 새 관리자 API 를 만들 때도
> `/api/v1/admin/{리소스}/...` 형태를 지킵니다.

---

**`/api/v1` 접두사를 벗기지 않습니다.**

```
브라우저   /api/v1/pets
    │
    ▼
게이트웨이  /api/v1/pets      ← 그대로
    │
    ▼
pet 서비스  /api/v1/pets      ← 그대로
```

**벗기면 공통 모듈 보안 체인의 `/api/v1/admin/**` 이 안 맞아 관리자 API 가 통째로 열립니다.**
오류가 안 나서 드러나지도 않습니다.

<br><br>

---

### 3-2. `{placeId}` 겹침 — 여기가 제일 까다롭습니다

```
장소 상세 화면 하나가 서비스 6개를 부름

GET /api/v1/places/{placeId}              place        기본 정보
GET /api/v1/places/{placeId}/documents    place        원문
GET /api/v1/places/{placeId}/verdict      verdict      판정
GET /api/v1/places/{placeId}/reviews      review       후기
GET /api/v1/places/{placeId}/conflicts    policy       조건 충돌
GET /api/v1/places/{placeId}/congestion   congestion   집중률


⛔ Path=/api/v1/places/**  하나로 두면

    /places/abc            ─┐
    /places/abc/verdict    ─┤
    /places/abc/reviews    ─┼──▶  전부 첫 라우트(place)로 감
    /places/abc/conflicts  ─┤       나머지 다섯은 place 에서 404
    /places/abc/congestion ─┘
                                  증상은 "장소 상세에서 판정만 안 뜬다"
                                  게이트웨이 로그에는 아무것도 안 남음


✅ {placeId} 로 두면

    Path=/api/v1/places/{placeId}              한 마디만 매칭
    Path=/api/v1/places/{placeId}/verdict      하위 경로는 안 걸림
                                               → 여섯이 서로 안 겹침
                                               → 목록에 적는 순서를 신경 쓸 필요가 없음
```

---

**한 곳만 예외였고 그것을 옮겼습니다.**

```
GET /api/v1/places/map     ⛔ {placeId} 에 map 이 걸려 place 로 감 (원래 search 소유)
        │
        ▼
GET /api/v1/search/map     ✅ 이동함 — 하는 일이 bbox 범위 조회라 그 자리가 맞음
```

**이제 예외가 0개라 순서를 신경 쓰지 않아도 됩니다.**

> **place 에 하위 경로가 새로 생기면 라우트도 함께 추가합니다.**
> `/**` 를 안 쓰므로 추가하지 않으면 404 입니다.

<br><br>

---

### 3-3. 자동 등록을 쓰지 않습니다

Spring Cloud Gateway 에는 **유레카에 등록된 서비스의 라우트를 자동으로 만드는 기능**이
있는데 쓰지 않습니다.

| | 명시 (우리 방식) | 자동 등록 |
|---|---|---|
| 새 서비스 | 라우트를 적어야 열림 | 저절로 열림 |
| 안 적으면 | **404** | — |
| `/internal/**` | 애초에 안 적음 | **자동으로 열림** |
| config-server | 안 적음 | **자동으로 열림** |
| 경로 형태 | `/api/v1/pets` | `/pet-service/**` |
| 라우트별 필터 | 가능 | 어려움 |

```
우리 설계의 핵심이 "무엇을 막는가" 인데 자동 등록은 정반대로 동작함

명시   안 적으면 안 열림      →  빠뜨리면 404 (실패 방향이 안전)
자동   블랙리스트로 막아야 함  →  빠뜨리면 열림 (실패 방향이 위험)
```

> 자동의 이점(*"서비스를 추가해도 게이트웨이를 안 고침"*)은 실익이 작습니다.
> 서비스를 추가하면 어차피 **config 2계층 파일 · 포트 배정 · Prometheus 타깃**을
> 만들어야 합니다.

<br><br>

---

### 3-4. 인증 없이 여는 경로

```yaml
# config/gateway-server.yml
app:
  gateway:
    permit-all:
      - /api/v1/auth/signup
      - /api/v1/auth/login
      - /api/v1/auth/refresh
      - /api/v1/auth/logout
      - /api/v1/auth/oauth/**
      - /api/v1/auth/password/reset-request
      - /api/v1/auth/password/reset
      - /api/v1/auth/email/verify-request
      - /api/v1/auth/email/verify
```

---

**같은 9줄이 auth 에도 있어야 합니다.**

```
config/gateway-server.yml   app.gateway.permit-all    게이트웨이가 토큰 없이 통과시킴
config/auth-service.yml     app.auth.permit-all       auth 보안 체인이 열어 둠
```

**한쪽에만 빠지면 401 이 나고, 어느 쪽인지는 로그로 갈립니다.**

| 어디에 빠짐 | 로그 |
|---|---|
| 게이트웨이 | 게이트웨이에 `토큰 쿠키가 없습니다` |
| auth | auth 에 `인증 실패 (401)` |

> **양쪽이 동시에 잘못 열리는 일은 없습니다.** 실패 방향이 안전한 쪽입니다.

---

**`permit-all` 목록이 비면 기동이 막힙니다.**

`GatewayAuthProperties` 가 검사합니다. **빈 목록은 *"열 경로가 없다"* 가 아니라
*"설정을 못 받았다"* 는 뜻**이고, 그대로 두면 증상이 **모든 요청 401** 이라 원인을 짚기
어렵습니다.

<br><br>

---

### 3-5. 라우트를 새로 여는 법

```
① config 저장소를 clone (처음 한 번)
        │
        ▼
② gateway-server.yml 의 routes 아래에 한 덩어리 추가
        │
        ▼
③ main 에 커밋 · push          이슈·PR 없이 직접
        │
        ▼
④ 반영 — 둘 중 하나
        │
        ├── POST :8080/actuator/refresh     재시작 없이
        └── 게이트웨이 재시작
        │
        ▼
⑤ GET :8080/actuator/gateway/routes 로 확인
```

---

**추가하는 모양입니다.**

```yaml
spring:
  cloud:
    gateway:
      server:
        webflux:
          routes:
            - id: auth-service
              uri: lb://auth-service
              predicates:
                - Path=/api/v1/auth/**

            - id: place-service                        # ← 추가
              uri: lb://place-service
              predicates:
                - Path=/api/v1/places/{placeId},/api/v1/places/{placeId}/documents
```

| 항목 | 규칙 | 어기면 |
|---|---|---|
| `id` | 서비스명과 같게 | 동작에는 지장 없음 |
| `uri` | `lb://` + **그 서비스의 `spring.application.name`** | 유레카에서 못 찾아 **503** |
| `predicates` | 보낼 경로. 쉼표로 여러 개 | 안 적으면 **404** |

> **YAML 은 들여쓰기가 곧 구조입니다.** `- id:` 앞 공백이 한 칸이라도 다르면
> 다른 목록으로 읽혀 **오류 없이 무시됩니다.** 위 항목을 복사해 값만 바꾸는 편이 안전합니다.

---

**인증 없이 열 경로라면 `permit-all` 에도 추가합니다.**
**auth 쪽 목록도 함께 봐야 합니다.** [3-4](#3-4-인증-없이-여는-경로) 참고.

<br><br>

---

## 4. 응답

게이트웨이도 **도메인 서비스와 똑같은 형태**로 응답합니다.

```json
{ "code": "...", "message": "...", "data": null, "traceId": "..." }
```

> **형태를 맞추는 이유** — 프론트는 401 이 게이트웨이에서 왔는지 도메인 서비스에서 왔는지
> **미리 알 수 없습니다.** 형태가 갈리면 두 벌 처리가 강제됩니다.

<br><br>

---

### 4-1. 게이트웨이가 직접 만드는 5가지

| code | HTTP | 언제 | 어느 단계 |
|---|---|---|---|
| `AUTHENTICATION_FAILED` | 401 | 쿠키 없음 · 서명 오류 · 만료 · `typ` 불일치 · claim 없음 | ⓒⓓⓔⓕ |
| `ACCESS_DENIED` | 403 | 관리자 경로인데 `role` 이 ADMIN 이 아님 | ⓖ |
| `ROUTE_NOT_FOUND` | 404 | 라우트 표에 없는 경로 | 라우팅 |
| `SERVICE_UNAVAILABLE` | 503 | 유레카에 그 서비스가 없거나 응답 없음 | 라우팅 |
| `INTERNAL_ERROR` | 500 | 그 밖의 예외 | 최종 폴백 |

---

**401 을 한 코드로 묶은 이유입니다.**

```
쿠키 없음 · 서명 오류 · 만료 · typ 불일치 · claim 없음
        │
        └──▶  프론트가 할 일은 전부 같음 — 로그인 화면으로 보내기
              (또는 /refresh 를 자동으로 부르는 인터셉터)
```

**응답에 사유를 적으면 토큰을 맞춰 보는 데 단서가 됩니다.** 원인은 로그에만 남깁니다.

```
log.debug("토큰 검증에 실패했습니다. path={}, reason={}", path, error.getMessage());
```

---

**앞의 둘은 공통 모듈과 문자열이 같습니다.**

| | 게이트웨이 | 공통 모듈 |
|---|---|---|
| `AUTHENTICATION_FAILED` | ✓ | ✓ **같은 문자열** |
| `ACCESS_DENIED` | ✓ | ✓ **같은 문자열** |
| `ROUTE_NOT_FOUND` | ✓ | ✗ 게이트웨이 전용 |
| `SERVICE_UNAVAILABLE` | ✓ | ✗ 게이트웨이 전용 |

**뒤의 둘을 공통 모듈에 넣지 않은 이유**는 도메인 서비스가 쓸 일이 없기 때문입니다.
*"공통에는 전 서비스가 쓰는 것만"* 기준입니다.

<br><br>

---

### 4-2. `traceId` 로 어느 층이 냈는지 알 수 있습니다

```
같은 401 이라도 낸 곳이 다름

브라우저  ──▶  게이트웨이  ──▶  도메인 서비스
                   │                 │
                   │                 └──▶  공통 모듈이 냄
                   │                        traceId 에 값이 있음
                   │                        (요청이 여기까지 왔다는 뜻)
                   │
                   └──▶  게이트웨이가 냄
                          traceId 가 null
                          (도메인 서비스까지 안 감)


                 code 문자열은 같음   AUTHENTICATION_FAILED · ACCESS_DENIED
                 게이트웨이 전용      ROUTE_NOT_FOUND · SERVICE_UNAVAILABLE
```

**조사할 때 이것부터 봅니다.** 같은 403 인데 `traceId` 유무로 **게이트웨이가 막았는지
도메인 서비스가 막았는지**가 갈립니다.

---

**`traceId` 가 `null` 인 또 다른 경우입니다.**

`observability` 프로파일을 안 켜면 Zipkin 이 없어 **스팬 자체가 안 만들어집니다.**
그때는 도메인 서비스 응답도 `null` 이라 층 구분에 못 씁니다.

```bash
docker compose --profile infra --profile platform --profile db --profile tools --profile observability up -d
```

<br><br>

---

### 4-3. 게이트웨이가 추적을 시작합니다

```
nginx (아직 없음)
   │
   ▼
게이트웨이         ◀── 지금은 여기가 최외곽
   │  traceId 를 새로 만듦
   │
   ├──▶  place 서비스     같은 traceId, span 추가
   ├──▶  verdict 서비스   같은 traceId, span 추가
   └──▶  review 서비스    같은 traceId, span 추가
```

**게이트웨이가 추적에 참여하지 않으면** 도메인 서비스들이 각자 새 trace 를 만들어
**요청 하나가 여러 갈래로 쪼개집니다.** 장소 상세처럼 6개를 병렬 호출하는 화면이면
6개로 흩어져 *"이 페이지가 왜 느린가"* 를 못 봅니다.

> **나중에 nginx 를 붙여도 중복 생성이 안 됩니다.** W3C 규약이 *"이미 있으면 이어받고
> 없으면 새로 만든다"* 이고 Micrometer 가 자동 처리합니다.
>
> ⚠ 단 nginx 가 `proxy_set_header` 로 `traceparent` 를 손수 조립하면 **조용히 안 이어집니다.**
> `"00-$request_id-$request_id-01"` 형태가 흔한데 **W3C 는 spanId 를 16자리로 요구**하는데
> `$request_id` 는 32자리라 파싱에 실패합니다. 오류가 아니라 무시됩니다.
> 권장은 `ngx_otel_module` 입니다.

<br><br>

---

### 4-4. API 에서 리다이렉트하지 않습니다

**잘못된 URL 을 로그인 페이지로 보내지 않고 404 를 냅니다.**

```
/ 로 오는 것        nginx 가 정적 파일로 처리      게이트웨이가 보지도 못함
                    SPA fallback + React catch-all

/api 로 오는 것     게이트웨이가 봄               404 ROUTE_NOT_FOUND
```

**API 에서 302 를 내면 셋이 깨집니다.**

| | 무엇이 |
|---|---|
| fetch·axios 가 302 를 자동으로 따라감 | JSON 자리에 `index.html` 이 도착해 `Unexpected token '<'` |
| 상태 코드가 302·200 이 됨 | **Grafana 에서 4xx 로 안 잡힘** |
| 응답 규약 밖 | `{code, message, data, traceId}` 가 아니게 됨 |

<br><br>

---

## 5. 설정값

**이 저장소의 `application.yml` 에는 세 줄뿐입니다.**

```yaml
spring:
  application:
    name: gateway-server
  config:
    import: "optional:configserver:http://${CONFIG_HOST:localhost}:8888"
  profiles:
    default: local
```

나머지는 전부 `paw-trail/config` 에서 내려옵니다.

```
config 저장소
├── application.yml              1계층   모든 서비스 공통
├── application-{env}.yml        3계층   주소 (유레카 · Loki · Zipkin)
└── gateway-server.yml           2계층   *라우트 · 공개키 · 인증 예외 · 액추에이터
```

<br><br>

---

### 5-1. gateway-server.yml 에 있는 것

```yaml
server:
  port: 8080

spring:
  cloud:
    gateway:
      server:
        webflux:
          routes:                       # 라우트 19개
            - id: auth-service
              uri: lb://auth-service
              predicates:
                - Path=/api/v1/auth/**
            # ... 18개 더

app:
  gateway:
    permit-all:                         # 인증 예외 9줄
      - /api/v1/auth/signup
      # ... 8줄 더

  jwt:
    public-key: |                       # PEM 원본 그대로
      -----BEGIN PUBLIC KEY-----
      MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA...
      -----END PUBLIC KEY-----
    claim:
      account-id: sub                   # auth 와 같은 이름이어야 함
      role: role
      type: typ

management:
  endpoints:
    web:
      exposure:
        include: health, info, prometheus, refresh, gateway
  endpoint:
    gateway:
      access: unrestricted              # *노출만으로는 안 켜짐
```

<br><br>

---

### 5-2. 공개키

**PEM 원본을 블록 스칼라(`|`)로 그대로 둡니다.**

| | 이 방식 | Base64 한 줄로 벗기는 안 |
|---|---|---|
| 가공 | 없음 | 헤더·개행을 손댐 |
| 오타가 생기면 | 낄 자리가 없음 | **"서명 검증 실패" 로만 나타남** |
| auth 키와 대조 | 눈으로 됨 | 안 됨 |

> **개인키는 여기 없습니다.** auth 가 환경변수로 받습니다.
> `config` 는 공개 저장소이고, **공개키는 확인만 되고 만들 수는 없어 공개돼도 무해합니다.**

---

**들여쓰기가 중요합니다.**

```yaml
    public-key: |
      -----BEGIN PUBLIC KEY-----      ← 이 줄들의 들여쓰기가 일정해야 함
      MIIBIjANBgkqhkiG...
      -----END PUBLIC KEY-----
```

어긋나면 `JwtDecoderConfig` 에서 키 파싱에 실패하고, **증상은 기동 실패입니다.**

---

**JWKS 엔드포인트를 쓰지 않는 이유입니다.**

```
기동 순서   config-server → eureka-server → gateway-server → 도메인 14개
                                                  │
                                                  └── auth 가 게이트웨이보다 나중에 뜸
                                                      게이트웨이가 뜰 때 키를 못 받음
```

**키 회전 계획도 없어** JWKS 의 이점이 성립하지 않는데 복잡도만 늘어납니다.

<br><br>

---

### 5-3. 값이 비면 기동을 막습니다

| 클래스 | 검사하는 것 | 안 막으면 |
|---|---|---|
| `JwtProperties` | `publicKey` | 키 파싱 오류로만 나와 **설정 누락인지 안 드러남** |
| | `claim` 자체 · 이름 3개 | `getClaimAsString(null)` 이 되어 **모든 요청이 500** |
| `GatewayAuthProperties` | `permitAll` 이 비지 않았는지 | **모든 요청 401** — 원인을 짚기 어려움 |

> **auth 보다 증상이 심합니다.** auth 는 claim 이름이 비면 *로그인할 때* 실패하지만
> 게이트웨이는 **모든 요청이 무너집니다.**

---

**검증을 추가하면 두 곳을 같이 고칩니다.**

```
config/gateway-server.yml                            실제 값
gateway-server/src/test/resources/application.yml    테스트용 사본   ← 놓치기 쉬움
```

**테스트는 `spring.cloud.config.enabled: false` 라 config 값이 하나도 안 내려옵니다.**
`app.jwt.claim.type` 을 넣을 때 실제로 이것을 빠뜨려 `contextLoads` 가 깨진 적이 있습니다.

<br><br>

---

### 5-4. 액추에이터 — `gateway` 만 예외입니다

```
management:
  endpoints:
    web:
      exposure:
        include: ... ,gateway      ← 이것만으로는 안 켜짐
  endpoint:
    gateway:
      access: unrestricted         ← 이것도 있어야 함
```

**Boot 문서상 `shutdown`·`heapdump` 를 뺀 모든 엔드포인트는 access 가 기본으로 열려
있는데 `gateway` 는 그 예외입니다.** 라우팅 구조가 통째로 드러나는 자리라서 그렇습니다.

| | 판별 |
|---|---|
| 켜짐 | 기동 로그 `Exposing 5 endpoints` |
| 안 켜짐 | `Exposing 4 endpoints` · `_links` 에도 안 나타남 · 404 |

> `read-only` 가 아니라 `unrestricted` 인 것은 **`POST /actuator/gateway/refresh` 가
> `read-only` 에서 막히기 때문**입니다.
>
> ⚠ **`/actuator/refresh` 로는 이 설정이 반영되지 않습니다.** 엔드포인트 등록은
> 기동 시점에 일어납니다.

---

**노출 범위에 주의합니다.**

```
배포     nginx 가 /api 만 게이트웨이로 넘김  →  /actuator 에 도달 불가
로컬     8080 에 직접 접근됨               →  누구나 라우팅 구조를 봄
```

로컬은 우리만 쓰므로 지금은 문제가 없지만 **차이를 알고 있어야 합니다.**

<br><br>

---

## 6. 코드 구조

**자바 파일이 9개뿐입니다.** 도메인이 없어 4계층 구조를 쓰지 않습니다.

<br><br>

---

### 6-1. 파일 트리

```
com.pawtrail.gatewayserver
│
├── GatewayServerApplication.java        @SpringBootApplication 하나뿐
│                                        (게이트웨이는 별도 활성화 애노테이션이 없음)
│
├── config/
│   ├── JwtProperties                    app.jwt.*   공개키 · claim 이름 3개
│   │                                    비면 기동 실패
│   ├── GatewayAuthProperties            app.gateway.permit-all
│   │                                    비면 기동 실패
│   └── JwtDecoderConfig                 공개키 PEM → RSAPublicKey → ReactiveJwtDecoder
│                                        빈 하나를 만들고 끝
│
├── filter/
│   ├── AuthenticationFilter             *이 서비스의 본체
│   │                                    GlobalFilter — ⓐ~ⓗ 8단계를 전부 처리
│   └── GatewayErrorHandler              라우팅 단계의 예외를 잡음
│                                        WebExceptionHandler, order = -2
│
└── response/
    ├── GatewayApiResponse               {code, message, data, traceId}
    ├── GatewayErrorCode                 5가지
    └── ErrorResponseWriter              응답 바디를 JSON 으로 씀

src/main/resources/
├── application.yml                      세 줄
└── logback-spring.xml                   콘솔 + Loki (dev · prod 만)
```

---

**서로 어떻게 부르는지입니다.**

```
요청
  │
  ▼
AuthenticationFilter ─────┬──▶  GatewayAuthProperties    permit-all 9줄 (config 에서)
  GlobalFilter            │
  ⓐ~ⓗ 8단계를 전부 처리   ├──▶  JwtProperties            claim 이름 3개 · 공개키 (config 에서)
                          │
                          ├──▶  JwtDecoderConfig  ──▶  ReactiveJwtDecoder
                          │        공개키 PEM 을 RSAPublicKey 로 변환         ⓓ 에서만 부름
                          │
                          └──▶  ErrorResponseWriter  ──▶  GatewayApiResponse
                                   401 · 403 을 씀              GatewayErrorCode
  │
  ▼
라우팅 (Spring Cloud Gateway 가 처리)
  │
  └── 404 · 503 · 500  ──▶  GatewayErrorHandler  ──▶  ErrorResponseWriter
                               WebExceptionHandler
                               order = -2 라 Boot 기본(-1)보다 먼저 잡음
```

<br><br>

---

### 6-2. WebFlux 입니다

도메인 서비스와 **가장 크게 다른 점**입니다.

| | 게이트웨이 | 도메인 서비스 |
|---|---|---|
| 웹 스택 | **WebFlux** (Netty) | WebMVC (Tomcat) |
| 요청 처리 | 논블로킹 · 리액티브 | 요청당 스레드 |
| 반환 타입 | `Mono<Void>` · `Mono<T>` | 그냥 객체 |
| 의존성 | `spring-cloud-starter-gateway-server-webflux` | `spring-boot-starter-webmvc` |

---

**⚠ 둘이 섞이면 기동에 실패합니다.**

```bash
./gradlew dependencies --configuration runtimeClasspath | grep webmvc
```

```powershell
.\gradlew dependencies --configuration runtimeClasspath | Select-String webmvc
```

**아무것도 안 나와야 정상입니다.** 누군가 `spring-boot-starter-webmvc` 를 전이로
끌고 오면 스프링이 어느 스택인지 판단하지 못합니다.

---

**Initializr 의 이름이 뒤집혀 있습니다.**

```
Gateway              →  webmvc   ("Servlet-based applications")
Reactive Gateway     →  webflux  ("reactive applications")
```

**예전에는 `Gateway` 가 곧 리액티브였는데** WebMVC 판이 나오면서 그 이름을 가져갔습니다.
옛 감각으로 고르면 **정확히 반대를 고르게 되고 빌드는 성공해서 바로 안 드러납니다.**

<br><br>

---

### 6-3. 공통 모듈을 쓰지 않습니다

플랫폼 3개(gateway · eureka · config) 공통 규칙입니다.

```
공통 모듈의 존재 이유   "도메인 서비스가 전부 쓰는 것"
플랫폼 3개             인프라 성격이라 그 기준 밖
```

---

**게이트웨이만의 이유가 하나 더 있습니다.**

```
TraceIdResponseAdvice 는 ResponseBodyAdvice 를 씀
        │
        └── ResponseBodyAdvice 는 spring-webmvc 에만 있음
                게이트웨이는 WebFlux 라 애초에 로딩될 수 없음
```

---

**나머지도 쓸 자리가 없습니다.**

| 공통 모듈이 주는 것 | 게이트웨이에서 |
|---|---|
| `BaseEntity` · JPA Auditing | JPA 가 없음 |
| `HeaderAuthenticationFilter` | **헤더를 주입하는 쪽**이지 읽는 쪽이 아님 |
| Outbox · Inbox | DB · Kafka 가 없음 |
| `CommonApiResponse` · `ErrorCode` | WebFlux 라 못 씀 → **같은 모양을 직접 만듦** |

---

**대가는 문자열 사본입니다.**

```
X-User-Id · X-User-Role
        │
        ├── common 의 AuthContextHeaders          도메인 서비스 13개가 참조
        └── 게이트웨이가 자기 저장소에 직접 적음     ← 4번째 사본
```

**어긋나면 필터가 헤더를 못 찾아 인증 없이 통과시키고, 그 뒤 경로 규칙에서 막혀
401 로만 나타납니다.** 토픽 이름을 공통 모듈에 두지 않은 것과 같은 성격이며,
완화책도 같습니다 — **문서를 단일 참조로 삼고 실물로 확인합니다.**

<br><br>

---

### 6-4. `SecurityConfig` 가 없습니다

```
spring-security-oauth2-jose 를 넣었지만
        │
        ├── spring-security-config  ✗ 안 딸려 옴
        └── spring-security-web     ✗ 안 딸려 옴
                │
                └── Boot 의 리액티브 보안 자동설정은
                    그 안의 ServerHttpSecurity 존재를 조건으로 함
                        → 애초에 켜지지 않음
                        → 무력화할 대상이 없음
```

**지문은 기동 로그입니다.**

```
Using generated security password: ...      ← 이 줄이 안 나오는 것이 정상
```

> ⚠ **누군가 `spring-boot-starter-security` 를 넣으면 그때는 기본 체인이 실제로 켜집니다.**
> 위 줄이 보이면 그것을 의심합니다. `build.gradle` 주석에도 적혀 있습니다.

<br><br>

---

## 7. 운영

<br><br>

---

### 7-1. 라우트를 고쳤는데 반영이 안 될 때

```
① config 저장소에 push 했나              git log 로 확인
        │
        ▼
② 설정 서버가 그것을 읽었나
        curl :8888/gateway-server/local   ← 내가 쓴 것이 보이나
        │
        ▼
③ 게이트웨이에 반영했나
        POST :8080/actuator/refresh       또는 재시작
        │
        ▼
④ 실제로 도는 것을 봤나
        curl :8080/actuator/gateway/routes
```

**②와 ④를 대조하는 것이 요령입니다.**

```
:8888/gateway-server/local        내가 쓴 것
:8080/actuator/gateway/routes     실제로 도는 것
```

둘이 다르면 **③을 안 한 것**이고, 둘 다 없으면 **①을 안 한 것**입니다.

<br><br>

---

### 7-2. 키 페어를 교체할 때

**auth 와 게이트웨이를 함께 바꿔야 합니다.** 짝이 어긋나면 **전 요청 401** 입니다.

```
① 새 키 페어 생성
        openssl genpkey -algorithm RSA -out private.pem -pkeyopt rsa_keygen_bits:2048
        openssl rsa -in private.pem -pubout -out public.pem
        │
        ▼
② 공개키 → config/gateway-server.yml 의 app.jwt.public-key
        블록 스칼라, 들여쓰기 주의
        │
        ▼
③ 개인키 → auth 의 환경변수 AUTH_JWT_PRIVATE_KEY_B64
        Base64 한 줄. 팀원 전원
        │
        ▼
④ config push → config-server 재시작 → 게이트웨이 재시작
        │
        ▼
⑤ 기존 로그인이 전부 풀림
        옛 키로 서명된 토큰은 새 공개키로 검증이 안 됨
```

**짝 확인은 이렇게 합니다.**

```bash
openssl rsa -in private.pem -pubout | diff - public.pem
```

**아무것도 출력되지 않으면 짝이 맞습니다.** `writing RSA key` 는 진행 메시지라
비교 대상이 아닙니다.

> **배포 때는 새 쌍을 만듭니다.** 로컬 개인키가 각자 컴퓨터에 돌아다니므로.

<br><br>

---

### 7-3. 이미지 굽고 올리기

```powershell
cd <gateway-server 경로>
.\gradlew clean build

$env:GPR_TOKEN | docker login ghcr.io -u <GitHub 아이디> --password-stdin
docker build -t ghcr.io/paw-trail/gateway-server:latest .
docker push ghcr.io/paw-trail/gateway-server:latest
```

**확인**

```powershell
docker run --rm --entrypoint sh ghcr.io/paw-trail/gateway-server:latest -c "ls -lh /app"
```

`app.jar` 가 수십 MB 면 정상입니다. **몇 KB 면 `-plain.jar` 가 담긴 것**인데,
`build.gradle` 에 `tasks.named('jar') { enabled = false }` 가 있어 지금은 안 생깁니다.

---

**컨테이너로 띄우기**

```bash
cd <infra 경로>
docker compose pull gateway-server
docker compose up -d
```

> `up -d` 만으로는 **이미지를 다시 받지 않습니다.** `pull` 이 먼저입니다.

---

**컨테이너에서는 `CONFIG_HOST` 를 줘야 합니다.**

```yaml
# infra/docker-compose.yml
  gateway-server:
    environment:
      SPRING_PROFILES_ACTIVE: dev
      CONFIG_HOST: config-server      # 컨테이너 안에서 localhost 는 자기 자신
```

**안 주면** `localhost:8888` 을 보는데 그건 게이트웨이 자기 자신이라 설정을 못 받고,
`app.jwt.public-key 가 비어 있습니다` 로 기동에 실패합니다. **의도한 동작입니다.**

<br><br>

---

### 7-4. 배포 순서 — auth 를 먼저

**`typ` 검사를 켤 때 실제로 겪을 자리입니다.**

```
① auth 배포              새 토큰에 typ 이 들어감
        │
        ▼
② 30분 기다림            옛 액세스 토큰(typ 없음)이 전부 만료됨
        │
        ▼
③ 게이트웨이 배포         typ 검사가 켜짐
```

반대로 하면 **②가 지나기 전에 발급된 토큰이 전부 401** 이 됩니다.

---

**config 를 고칠 때도 순서가 있습니다.**

```
① config 저장소에 새 값 push          예: app.jwt.claim.type
        │
        ▼
② docker compose restart config-server
        │
        ▼
③ 게이트웨이 배포
```

**①을 안 하고 ③을 하면 새 검증에 걸려 기동이 막힙니다.** 의도한 동작입니다.

<br><br>

---

### 7-5. 무중단 배포는 blue-green 입니다

```
                   ┌──▶  gateway (blue)   8080    ← nginx 가 지목 중
nginx upstream ────┤
                   └──▶  gateway (green)  8180    새 버전을 띄움
                                │
                                ├── 안 뜨면   upstream 을 안 바꾸면 그만
                                └── 뜨면     upstream 을 green 으로 전환
```

**방식을 가르는 기준은 "누가 이 서비스를 찾느냐" 입니다.**

| 누가 찾나 | 방식 |
|---|---|
| nginx 가 upstream 으로 지목 | **blue-green** — 게이트웨이 |
| 유레카에 등록돼 게이트웨이가 찾음 | 롤링 — 도메인 서비스 |

> **green 이 안 뜨면 upstream 을 안 바꾸면 되므로 롤백이랄 것도 없습니다.**

<br><br>

---

## 8. 왜 이렇게 만들었나

각 항목은 **문제 → 고른 것 → 버린 것** 순서입니다.

<br><br>

---

### 8-1. 인증을 여기서 검증하는 이유

**auth 가 검증하게 하면 이렇게 됩니다.**

```
모든 요청  ──▶  게이트웨이  ──▶  auth (검증)  ──▶  목적지
                                   ▲
                                   └── 전체 트래픽의 병목이자 단일 실패 지점
```

**JWT 는 서명만 확인하면 되므로** 게이트웨이가 직접 하는 편이 맞습니다.

결과로 **JWT 라이브러리가 gateway·auth 둘에만** 들어가고 도메인 14개는 토큰을 아예 안 봅니다.

---

**이 신뢰 모델의 전제는 VPC 격리입니다.**

```
EC2 가 전부 프라이빗 서브넷
보안그룹이  ALB → edge,  edge → core·app  으로 잠겨 있음
        │
        └── 도메인 서비스에 바깥에서 도달할 방법이 없음
```

**하지만 그것만으로는 부족합니다.** VPC 는 *"게이트웨이를 정상 경로로 통과하는 위조 헤더"*
를 못 막습니다. 그것이 ⓐ 단계가 있는 이유입니다. [2-2](#2-2-헤더-제거가-맨-앞이어야-하는-이유) 참고.

---

**Keycloak 은 이 층의 대안이 아닙니다.**

```
Keycloak    발급 층 (IdP)      auth 의 자리
게이트웨이    검증 층            *Keycloak 을 써도 여기는 그대로 필요함
```

<br><br>

---

### 8-2. `NimbusReactiveJwtDecoder` 를 쓰는 이유

| 고름 | 버림 |
|---|---|
| `spring-security-oauth2-jose` | JJWT · 직접 구현 |
| **Spring Security 가 자기 리소스 서버 구현에 실제로 쓰는 것** | JJWT 는 나쁜 선택이 아니고 실제로 많이 쓰임 |
| `withPublicKey()` 로 만들면 **RS256 이 고정** | 직접 구현은 알고리즘 혼동·`nbf`·클럭 스큐를 다 짜야 함 |

**기준이 "코드가 짧은가" 가 아닌 이유**는 여기가 **유일한 검증 지점이고 2차 방어선이
없기 때문**입니다. 도메인 서비스는 토큰을 아예 안 보므로 **여기가 뚫리면 뒤쪽 14개가
전부 뚫립니다.**

---

**`withPublicKey()` 가 막는 공격입니다.**

```
공격자가 토큰 헤더의 alg 를 RS256 → HS256 으로 바꿈
        │
        └──▶  공개키를 HMAC 비밀키로 써서 유효한 서명을 만듦
                공개키는 공개돼 있으므로 가능함
                        │
                        └── withPublicKey() 는 RS256 을 고정하므로 안 통함
```

<br><br>

---

### 8-3. 라우팅 설계

**접두사를 벗기지 않는 이유** — 공통 모듈의 `/api/v1/admin/**` 한 줄 때문입니다.
벗기면 **오류 없이 관리자 API 전체가 열립니다.**

벗기는 안은 실패 지점을 셋(Vite rewrite · nginx rewrite · 게이트웨이 되붙이기) 만들고,
하나만 어긋나면 *개발에서는 되는데 배포에서 안 되는* 형태가 됩니다.

---

**config 에 두는 이유** — 라우트 개방이 앞으로 **14번** 일어납니다.
코드에 있으면 그때마다 빌드·이미지·배포 사이클을 돌게 됩니다.

| | config yml | 자바 `RouteLocator` |
|---|---|---|
| 라우트 추가 | push + refresh | **빌드 → 이미지 → blue-green 배포** |
| 오타 검증 | 없음 | 컴파일 |
| 오타 증상 | **404 — 첫 호출에서 드러남** | — |
| 환경별 차이 | 4계층 파일로 | 코드 분기 |

> **대가는 영향 범위입니다.** 지금 config 오타는 *"그 서비스 하나"* 가 포트를 못 잡는
> 정도인데, 라우트가 들어오면 **오타 하나로 특정 API 전체가 404** 가 됩니다.

---

**API 경로를 전면 재설계하지 않은 이유입니다.**

```
"접두사만 보면 서비스를 안다" 는 재설계해도 성립하지 않음
        user   /users · /favorites · /visits · /itineraries    넷
        pet    /pets · /breeds                                 둘
        review /reviews · /places/*/reviews                    둘

"한 접두사 아래 여러 서비스" 를 어기는 곳은 /api/v1/places/ 하나뿐
        → 전면 재설계인데 고치는 문제는 한 군데
```

**하위 경로 형태가 오히려 낫습니다.** 장소 상세가 *"한 장소에 대해 6가지를 묻는 화면"*
인데 경로가 그 관계를 그대로 드러냅니다.

<br><br>

---

### 8-4. 응답 형태를 도메인 서비스와 맞춘 이유

**프론트는 응답의 출처를 미리 알 수 없습니다.**

```
같은 401 이
    게이트웨이에서 올 수도
    도메인 서비스에서 올 수도 있음
        │
        └── 형태가 갈리면 두 벌 처리가 선택이 아니라 강제가 됨
            "code !== 'SUCCESS' 한 줄로 판단한다" 가 무너짐
```

---

**바디 없이 상태 코드만 내는 것은 안 됩니다.**

공통 모듈 검증 때 401 이 `Content-Length: 0` 으로 나가 **프론트가 아무 정보도 못 받는
상황을 실제로 겪었고** 그래서 `CustomSecurityExceptionHandler` 를 만들었습니다.

---

**`GATEWAY_` 접두사를 붙이지 않은 이유입니다.**

```
출처는 로그로 이미 드러남      게이트웨이가 자기 로그에 남기고 Loki 로 감
접두사를 붙이면              code === 'AUTHENTICATION_FAILED' 로 분기하던 곳이
                            게이트웨이 응답을 놓치게 됨
```

<br><br>

---

### 8-5. `WebExceptionHandler` 를 쓴 이유

`ErrorWebExceptionHandler`(Boot 패키지)가 아니라 **`WebExceptionHandler`(Spring Framework
코어)** 를 구현합니다.

```
근거    Boot 4 로 오면서 패키지가 여러 번 옮겨졌음
        이 대화에서 Boot 4 세부로 두 번 틀린 적이 있음 (eureka HttpClient · gateway access)
        코어 인터페이스는 그 위험이 작음

order   Boot 기본이 -1 이므로 -2 면 먼저 잡음
        하는 일은 같음
```

**PEM → `RSAPublicKey` 변환도 같은 이유**로 Spring Security 유틸리티가 아니라
자바 표준(`KeyFactory` + `X509EncodedKeySpec`)을 씁니다. **3줄이면 되는 것에
판올림마다 자리가 바뀌는 유틸리티를 쓸 이유가 약합니다.**

<br><br>

---

### 8-6. `sub` · `role` 이라는 claim 이름

`sub` 는 **JWT 규격(RFC 7519)의 표준 항목**이라 `Jwt.getSubject()` 와 로깅 도구가
그 자리를 봅니다. `accountId` 로 바꾸면 이름은 명확하지만 **표준 자리를 비워 두게 됩니다.**

```
sub    accountId    표준
role   role         우리가 정한 것
typ    access       우리가 정한 것 (2026.9.1 추가)
```

**어긋나면 서명은 통과하는데 값이 비어 401 이 납니다.** 지문은 게이트웨이 로그의
`토큰에 필요한 값이 없습니다` 입니다.

<br><br>

---

## 9. 막히기 쉬운 자리

<br><br>

---

### 9-1. 라우팅

| 증상 | 원인 | 확인 |
|---|---|---|
| 모든 경로가 404 | **config 를 못 받음** | `/actuator/gateway/routes` 가 빈 배열인지 |
| 한 경로만 404 | 라우트를 안 적음 | config 의 `routes` 에 그 경로가 있는지 |
| `/actuator/gateway/routes` 가 404 | `management.endpoint.gateway.access` 누락 | 기동 로그가 `Exposing 4 endpoints` |
| 503 | 유레카에 그 서비스가 없음 | `:8761` 에 보이는지 · `uri` 의 `lb://` 이름이 맞는지 |
| 라우트를 고쳤는데 그대로 | refresh 를 안 함 | [7-1](#7-1-라우트를-고쳤는데-반영이-안-될-때) |
| 하위 경로가 엉뚱한 서비스로 | `/**` 를 씀 | [3-2](#3-2-placeid-겹침--여기가-제일-까다롭습니다) |

<br><br>

---

### 9-2. 인증

| 증상 | 원인 |
|---|---|
| **모든 요청 401** | `permit-all` 이 비었거나 공개키가 auth 개인키와 짝이 아님 |
| 로그인이 401 | `permit-all` 에 그 경로가 빠짐. 로그에 `토큰 쿠키가 없습니다` |
| 정상 토큰인데 401 | **`typ` 검사** — 리프레시 토큰을 넣었거나 `typ` 없는 옛 토큰 |
| 관리자 API 가 403, `traceId` null | **게이트웨이가 막음** — `role` 이 ADMIN 이 아님. 재로그인했는지 |
| 관리자 API 가 403, `traceId` 있음 | 도메인 서비스가 막음 |
| 위조 헤더를 붙였는데 통과됨 | ⓐ 가 동작 안 함 — **있으면 안 되는 상태** |

<br><br>

---

### 9-3. 기동

| 로그 | 원인 |
|---|---|
| `app.jwt.public-key 가 비어 있습니다` | config 를 못 받음. `CONFIG_HOST` 확인 |
| `app.gateway.permit-all 이 비어 있습니다` | 같음 |
| `Exposing 4 endpoints` | `gateway` 액추에이터 access 누락 |
| `Using generated security password` | **누군가 `spring-boot-starter-security` 를 넣음** |
| `contextLoads` 실패 | 테스트 리소스에 새 설정값을 안 넣음 |
| 포트가 8080 인데 라우트가 0개 | **config 미수신.** 포트로는 판별이 안 되는 서비스 |
| `NoClassDefFoundError` | IntelliJ 클래스패스. **Gradle 재동기화 1순위** |

<br><br>

---

### 9-4. 코드를 고칠 때

| 하려는 것 | 주의 |
|---|---|
| `Properties` 에 검증 추가 | **테스트 리소스에도 값을 넣어야** `contextLoads` 통과 |
| 필터 단계 추가 | **ⓐ 보다 앞에 두지 말 것.** 헤더 제거가 언제나 먼저 |
| 새 에러 코드 | *"도메인 서비스도 쓰나"* 를 물음. 안 쓰면 게이트웨이에만 |
| 의존성 추가 | **`spring-boot-starter-security` 를 넣지 말 것** |
| | `webmvc` 가 전이로 들어오지 않는지 확인 |
| 헤더 이름 변경 | **공통 모듈의 `AuthContextHeaders` 와 함께** 고쳐야 함 |
| claim 이름 변경 | **auth 의 config 와 함께** 고쳐야 함. 양쪽 다 |

<br><br>

---

### 9-5. 환경

| | 주의 |
|---|---|
| PowerShell | `curl` 은 별칭이라 **`curl.exe`** |
| `bootRun` | 80% 에서 멈춘 것처럼 보이는 것이 정상 — **앱이 떠 있다는 표시** |
| IntelliJ 실행 버튼 | 써도 됨. 기동 로그가 `build\classes\java\main started by` 면 그것 |
| `host.docker.internal` | 호스트에서는 LAN IP, 컨테이너에서는 도커 게이트웨이로 풀림. **한 이름이 양쪽에서 통함** |

<br><br>

---

## 10. 아직 안 한 것

<br><br>

---

### 10-1. nginx 를 붙일 때

```
지금            브라우저  ──▶  게이트웨이(8080)  ──▶  서비스
붙인 뒤          브라우저  ──▶  nginx(80)  ──▶  게이트웨이  ──▶  서비스
                                  │
                                  ├── / 로 오는 것    정적 파일 (SPA)
                                  └── /api 로 오는 것  게이트웨이
```

| 할 것 | 주의 |
|---|---|
| SPA fallback | `try_files $uri $uri/ /index.html` |
| `proxy_pass http://gateway;` | **뒤에 슬래시를 붙이지 말 것** — 붙이면 접두사가 벗겨져 관리자 경로가 열림 |
| `traceparent` | **`ngx_otel_module` 로.** 손수 조립하면 형식 불일치로 조용히 안 이어짐 |
| `X-Forwarded-For` | 게이트웨이가 이것을 넣게 하고 **몇 번째 값을 읽을지** 정함 |
| `/actuator` 차단 | nginx 가 `/api` 만 넘기면 자동으로 됨 |

> **`X-Forwarded-For` 는 auth 의 `ip_address` 와 묶여 있습니다.**
> 지금 auth 는 그 값을 `null` 로 두고 있고, nginx 를 붙일 때 함께 정합니다.

<br><br>

---

### 10-2. 배포할 때

| 무엇 | 로컬 | 배포 |
|---|---|---|
| RS256 키 | 로컬 쌍 | **새 쌍** — 공개키도 `gateway-server-prod.yml` 로 |
| `/actuator` | 8080 에 직접 접근됨 | nginx 가 `/api` 만 넘겨 도달 불가 |
| 인스턴스 | 1개 (8080) | blue-green (8080 · 8180) |

<br><br>

---

### 10-3. 판단만 남은 것

| 무엇 | 언제 |
|---|---|
| Swagger 를 게이트웨이 뒤에 통합할지 | **user·pet 이 생겨 Swagger 가 여러 개가 되면** 자연히 다시 나옴 |
| | 함께 볼 것 — `permit-all` 에 넣을지(넣으면 누구나 API 목록을 봄) · 배포에서 끄는 문제 |
| `observability` 로 `traceId` 확인 | 아직 안 해 봄. 지금은 항상 `null` |

<br><br>

---

### 10-4. 프론트에 전달할 것

| 무엇 | 왜 |
|---|---|
| Vite 프록시 `'/api': { target: 'http://localhost:8080', changeOrigin: true }` | **`rewrite` 금지** — 접두사가 벗겨지면 관리자 경로가 열림 |
| React Router catch-all (`*`) | 잘못된 URL 처리는 프론트에서. **백엔드는 API 에서 리다이렉트하지 않음** |
| 게이트웨이 응답 형태 | 401·403·404·503 도 `{code, message, data, traceId}` |
| `ROUTE_NOT_FOUND` · `SERVICE_UNAVAILABLE` | **게이트웨이에서만 나옴** |
| 401 인터셉터 | 자동 `/refresh` 후 재시도 + 동시 401 큐잉 |

<br><br>

---

## 11. 용어

공통 용어는 `service-template` README 11장에, 인증 용어는 `auth-service` README 11장에
있습니다. **여기는 게이트웨이에서만 쓰는 말**입니다.

| 용어 | 뜻 |
|---|---|
| **게이트웨이** | 모든 요청이 처음 들어오는 문. 인증을 확인하고 어느 서비스로 보낼지 정함 |
| **라우트** | *"이 경로로 오면 저 서비스로 보낸다"* 는 규칙 하나 |
| **predicate** | 라우트가 맞는지 판단하는 조건. 우리는 `Path=` 만 씀 |
| **`lb://`** | LoadBalancer 가 처리하라는 표시. 뒤의 이름을 유레카에서 찾음 |
| **`{placeId}`** | 경로 변수. **한 마디만 매칭**하므로 하위 경로에는 안 걸림 |
| **`/**`** | 하위 전부를 매칭. 여러 서비스가 섞인 접두사에는 쓰면 안 됨 |
| **GlobalFilter** | 모든 라우트에 적용되는 필터. `AuthenticationFilter` 가 그것 |
| **WebFlux** | 논블로킹 · 리액티브 웹 스택. Netty 위에서 돎 |
| **Netty** | WebFlux 가 쓰는 서버. 도메인 서비스의 Tomcat 자리 |
| **`Mono<Void>`** | *"나중에 값 없이 끝난다"* 는 리액티브 타입. 필터 반환형 |
| **`ServerWebExchange`** | 요청·응답을 함께 담은 객체. WebFlux 의 `HttpServletRequest` 자리 |
| **`WebExceptionHandler`** | 처리되지 않은 예외를 잡는 자리. order 가 작을수록 먼저 |
| **액추에이터** | 상태·지표를 보여 주는 엔드포인트 묶음. `/actuator/**` |
| **`/actuator/gateway/routes`** | **실제로 도는 라우트 목록.** 이 서비스의 유일한 판별 수단 |
| **`/actuator/refresh`** | config 저장소의 값을 다시 읽는 엔드포인트 |
| **blue-green** | 새 버전을 따로 띄우고 트래픽을 한 번에 옮기는 배포 |
| **upstream** | nginx 가 요청을 넘길 대상. blue-green 에서 이것을 바꿈 |
| **알고리즘 혼동 공격** | 토큰의 `alg` 를 RS256→HS256 으로 바꿔 **공개키를 비밀키로 써서** 서명을 위조하는 것 |
| **`withPublicKey()`** | 디코더를 만들 때 RS256 을 고정해 위 공격을 막는 방법 |
| **traceparent** | 추적 정보를 담은 W3C 표준 헤더. `00-{traceId}-{spanId}-{flags}` |
| **span** | 추적의 한 구간. 층마다 늘어나고 traceId 는 하나 |
