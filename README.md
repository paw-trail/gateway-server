# gateway-server

함께하개(paw-trail)의 **API 게이트웨이**입니다. 브라우저에서 오는 모든 요청이 이곳을 지나며, 경로를 보고 어느 서비스로 보낼지 정합니다.

인증도 여기서 끝납니다. 쿠키에 담긴 토큰을 확인해 사용자 정보를 헤더로 바꿔 넣어 주고, 뒤쪽 서비스들은 토큰을 다루지 않습니다.

---

## 1. 이 서비스가 하는 일

### 1-1. 요청 하나가 지나가는 길

```
브라우저
   │  GET /api/v1/places/{id}/verdict
   │  Cookie: access_token=...
   ↓
nginx                        /api 는 이곳으로, / 는 정적 파일로
   ↓
gateway-server
   │
   ├─ ⓐ 들어온 X-User-Id · X-User-Role 헤더를 지움
   ├─ ⓑ 인증 없이 열어 둔 경로인가?          예 → 그대로 통과
   ├─ ⓒ 쿠키에서 액세스 토큰을 꺼냄           없음 → 401
   ├─ ⓓ 공개키로 서명을 확인                실패·만료 → 401
   ├─ ⓔ 토큰에서 사용자 정보를 꺼냄
   ├─ ⓕ 관리자 경로인데 권한이 없으면        → 403
   └─ ⓖ X-User-Id · X-User-Role 을 넣고 라우팅
   ↓
verdict-service              헤더만 읽음, 토큰은 보지 않음
```

### 1-2. ⓐ가 가장 먼저인 이유

**들어온 인증 헤더를 지우는 것이 다른 무엇보다 먼저입니다.** 뒤쪽 서비스들은 이 게이트웨이가 넣어 준 헤더를 믿도록 만들어져 있으므로, 바깥에서 들어온 같은 이름의 헤더가 지워지지 않은 채 통과하면 그대로 신뢰됩니다.

```
POST /api/v1/auth/login
     X-User-Id: <남의 계정 식별자>
     X-User-Role: ADMIN
```

인증 없이 열어 둔 경로라고 헤더를 지우지 않으면 안 됩니다. **모든 요청에서 조건 없이 먼저 지웁니다.** 순서를 ⓑ 뒤로 옮기면 위 요청이 그대로 통과합니다.

이 실패는 오류로 나타나지 않고 **정상 응답으로 나타납니다.**

### 1-3. 열어 둔 경로만 통과시킵니다

인증 없이 부를 수 있는 경로를 목록으로 두고, **목록에 없으면 전부 검증합니다.** 반대로 "여기는 열고 저기만 막는다" 방식을 쓰지 않는 이유는, 새 경로가 생겼을 때 목록에 넣지 않으면 인증 없이 열려 버리기 때문입니다.

지금 방식에서는 목록에 넣지 않으면 401이 나므로 **빠뜨렸을 때 시끄럽게 드러납니다.**

### 1-4. 라우트에 적지 않은 경로는 열리지 않습니다

라우트를 하나씩 적어 두고 그 목록에 없는 경로는 404를 돌려줍니다. 유레카에 등록된 서비스를 자동으로 라우팅하는 기능이 있지만 쓰지 않습니다.

자동으로 하면 **막기로 한 것들이 함께 열리기 때문입니다.**

| 막아야 하는 것 | 왜 |
|---|---|
| `/internal/**` | 서비스끼리와 Jenkins 만 부르는 경로입니다. 인증이 없는 대신 네트워크로 격리해 두었습니다 |
| `config-server` | 전 서비스의 설정 원본을 내려주는 곳이라 한 번의 호출로 내부 구조가 통째로 드러납니다 |
| `ingest` · `extract` | 브라우저가 부를 API 가 하나도 없습니다 |

적지 않으면 막히므로 따로 차단 규칙을 쓰지 않습니다.

---

## 2. 로컬 실행

### 2-1. 준비

설정을 `config-server` 에서 받으므로 **먼저 그것을 띄워야 합니다.**

```powershell
git clone https://github.com/paw-trail/gateway-server.git
cd gateway-server
.\gradlew bootRun
```

프로파일을 지정하지 않으면 `local` 로 동작하며 Loki 전송이 꺼집니다.

`bootRun` 은 앱이 떠 있는 동안 끝나지 않는 작업이므로 **Gradle 진행률이 80% 근처에서 멈춘 것처럼 보이는 것이 정상입니다.** 콘솔에 `Started GatewayServerApplication` 이 찍혔는지로 판단합니다.

### 2-2. 환경변수

| 이름 | 기본값 | 언제 지정하는가 |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | (없음, `local` 로 동작) | 컨테이너에서 `dev` 또는 `prod` |
| `CONFIG_HOST` | `localhost` | 컨테이너에서 `config-server` 또는 노드 주소 |

이 서비스에는 비밀 값이 없습니다. 토큰 검증에 쓰는 것은 **공개키**이며 config 저장소에 들어 있습니다. 서명을 만드는 개인키는 인증 서비스만 가지고 있고 환경변수로 주입합니다.

### 2-3. 기동 순서

```
config-server  →  eureka-server  →  gateway-server  →  도메인 서비스 14개
```

도메인 서비스보다 먼저 떠도 됩니다. 라우트에 적힌 서비스가 없으면 그 경로만 503이 되고 나머지는 정상입니다.

---

## 3. 제대로 도는지 확인하기

### 3-1. 라우트가 실제로 무엇인지

```powershell
curl.exe http://localhost:8080/actuator/gateway/routes
```

**설정에 적은 것과 실제로 도는 것을 대조하는 자리입니다.** 규칙이 config 저장소에 있으므로, 내가 쓴 것과 서버가 물고 있는 것을 각각 확인해 맞춰 봅니다.

```powershell
curl.exe http://localhost:8888/gateway-server/local
```

PowerShell 의 `curl` 은 `Invoke-WebRequest` 의 별칭이라 응답이 객체로 감싸집니다. 원문을 보려면 확장자까지 적습니다.

### 3-2. 포트로는 설정 수신 여부를 알 수 없습니다

`spring.config.import` 에 `optional:` 이 붙어 있어 설정 서버가 없어도 기동됩니다. 그런데 **이 서비스는 원래 포트가 8080 이라, 설정을 못 받아도 같은 포트에 그대로 뜹니다.**

서비스 레지스트리는 8761 이 아닌 8080 에 뜨는 것으로 바로 드러나지만 여기는 그렇지 않습니다. 대신 **라우트 목록이 비어 있는 것**으로 가려냅니다. 라우팅 규칙이 config 저장소에 있기 때문입니다.

```
routes 가 비어 있음  →  설정을 못 받음
모든 요청이 404      →  같은 원인
```

### 3-3. 헤더가 실제로 주입되는지

가장 확실한 확인은 **뒤쪽 서비스가 만든 데이터의 작성자 값**을 보는 것입니다. 감사 컬럼의 `created_by` 가 `SYSTEM` 이 아니라 실제 계정 식별자여야 합니다. `SYSTEM` 이면 헤더가 실리지 않은 것이며, 이 경우 오류가 나지 않으므로 데이터를 열어 보기 전까지 알 수 없습니다.

### 3-4. 상태 확인

```powershell
curl.exe http://localhost:8080/actuator/health
```

---

## 4. 공통 모듈을 의존하지 않습니다

플랫폼 3종(`config-server`, `eureka-server`, `gateway-server`)은 공통 모듈(`com.pawtrail.common`)을 사용하지 않습니다. 공통 모듈은 도메인 서비스가 공유하는 것들을 담고 있고, 플랫폼은 성격이 다릅니다.

이 서비스에는 이유가 하나 더 있습니다. **공통 모듈은 서블릿 환경을 전제로 만들어졌고 이 서비스는 WebFlux 로 돕니다.** 응답을 감싸는 장치와 인증 필터가 모두 서블릿 전용이라 이곳에서는 애초에 동작하지 않습니다.

| | 도메인 서비스 | 이 서비스 |
|---|---|---|
| 스택 | 서블릿 | WebFlux |
| 보안 설정 | `SecurityFilterChain` | `SecurityWebFilterChain` |
| 필터 | `OncePerRequestFilter` | `WebFilter` · `GlobalFilter` |

클래스 이름이 모두 다르므로 공통 모듈의 코드를 그대로 옮겨 쓸 수 없습니다.

### 4-1. 대신 형태만 맞춥니다

응답 규약은 도메인 서비스와 같은 모양을 씁니다. 프론트엔드가 받은 응답이 게이트웨이에서 온 것인지 서비스에서 온 것인지 미리 알 수 없으므로, 형태가 다르면 두 가지를 모두 다뤄야 하기 때문입니다.

```json
{
  "code": "AUTHENTICATION_FAILED",
  "message": "인증에 실패하였습니다.",
  "data": null,
  "traceId": "6a9125bc..."
}
```

| 상황 | 상태 | code |
|---|---|---|
| 쿠키 없음 · 서명 실패 · 만료 | 401 | `AUTHENTICATION_FAILED` |
| 관리자 경로인데 권한 없음 | 403 | `ACCESS_DENIED` |
| 라우트에 없는 경로 | 404 | `ROUTE_NOT_FOUND` |
| 대상 서비스가 없음 | 503 | `SERVICE_UNAVAILABLE` |

앞의 둘은 공통 모듈과 같은 문자열입니다. 뒤의 둘은 이 서비스에서만 나오는 것이라 여기에만 있으며, 공통 모듈에 넣지 않습니다.

---

## 5. 설정은 config 저장소에 있습니다

이 저장소의 `application.yml` 에는 세 줄만 있습니다.

```yaml
spring:
  application:
    name: gateway-server
  config:
    import: "optional:configserver:http://${CONFIG_HOST:localhost}:8888"
  profiles:
    default: local
```

나머지는 `paw-trail/config` 에서 내려옵니다.

| 계층 | 파일 | 이 서비스가 받는 값 |
|:---:|---|---|
| 1 | `application.yml` | 액추에이터 노출 범위, graceful shutdown, 로깅 레벨 |
| 2 | `gateway-server.yml` | 포트 8080, **라우팅 규칙**, **공개키와 토큰 항목 이름**, **인증 예외 목록**, 액추에이터 노출 |
| 3 | `application-{env}.yml` | 유레카 주소, Loki · Zipkin 주소 |

### 5-1. 라우팅 규칙을 설정으로 둔 이유

도메인 서비스가 하나씩 완성될 때마다 라우트를 열어야 합니다. 규칙이 이 저장소에 있으면 **그때마다 이미지를 다시 만들고 무중단 배포를 한 번씩 돌게 됩니다.** 설정에 있으면 커밋하고 `/actuator/refresh` 를 부르면 끝납니다.

다만 대가가 있습니다. 설정의 오타 하나가 **특정 경로 전체를 404로 만듭니다.** 그래서 3-1의 대조 확인을 습관으로 둡니다.

### 5-2. 경로 규칙

같은 `/api/v1/places/` 아래에 서비스 여섯 개가 섞여 있습니다. 장소 상세 화면에서 브라우저가 여러 개를 한꺼번에 부르기 때문이며, 경로는 장소를 중심으로 짜여 있고 소유 서비스는 갈려 있습니다.

```
/api/v1/places/{placeId}                place
/api/v1/places/{placeId}/documents      place
/api/v1/places/{placeId}/verdict        verdict
/api/v1/places/{placeId}/reviews        review
/api/v1/places/{placeId}/conflicts      policy
/api/v1/places/{placeId}/congestion     congestion
```

**여기에 `/**` 를 쓰면 안 됩니다.** 하위 경로를 모두 먹어 버려 나머지 네 서비스로 갈 요청이 전부 첫 번째 라우트로 갑니다. 게이트웨이는 처음 맞는 라우트에서 멈추기 때문입니다.

`{placeId}` 는 한 마디만 맞추므로 위 여섯이 서로 겹치지 않고, **순서를 신경 쓰지 않아도 됩니다.**

대신 place 에 하위 경로가 새로 생기면 **라우트도 함께 추가해야 합니다.** 추가하지 않으면 404가 납니다.

---

## 6. 트러블슈팅

### 모든 요청이 404 입니다

설정을 받지 못한 것입니다. 3-2를 확인합니다. 라우트 목록이 비어 있다면 `config-server` 가 떠 있는지, `gateway-server.yml` 이 저장소에 있는지 봅니다.

파일 이름이 `spring.application.name` 과 다르면 **오류 없이 그 계층만 빠진 채 내려옵니다.**

### 특정 경로만 404 입니다

라우트에 그 경로가 없는 것입니다.

```powershell
curl.exe http://localhost:8080/actuator/gateway/routes
```

`/api/v1/places/` 아래라면 5-2를 확인합니다. 하위 경로를 새로 만들었는데 라우트를 추가하지 않은 경우가 가장 많습니다.

### 인증했는데 401 이 납니다

세 가지를 순서대로 봅니다.

1. **공개키가 제대로 내려왔는지** — 설정 응답의 값에 줄바꿈이 살아 있어야 합니다. 여러 줄짜리 값이라 들여쓰기가 어긋나면 잘립니다
2. **서명 알고리즘이 맞는지** — RS256 으로 고정되어 있어 인증 서비스가 다른 방식으로 서명하면 전부 실패합니다
3. **토큰 안의 이름이 맞는지** — 게이트웨이가 꺼내는 이름과 인증 서비스가 넣는 이름이 같아야 합니다. 다르면 401 로만 나타나고 원인이 드러나지 않습니다

### 기동할 때 임의의 비밀번호가 로그에 찍힙니다

```
Using generated security password: ...
```

**보안 프레임워크의 기본 설정이 켜진 것이며, 정상 상태에서는 이 줄이 나오지 않습니다.**

이 서비스는 토큰 검증에 필요한 부분만 쓰고 보안 프레임워크 전체를 얹지 않습니다. 그 의존성은 웹 계층 모듈을 가져오지 않으므로 기본 설정이 켜질 조건을 만족하지 않습니다.

이 줄이 보인다면 **누군가 `spring-boot-starter-security` 를 추가한 것입니다.** 그러면 모든 요청이 인증을 요구하게 되고, 그것을 다시 끄는 설정을 따로 두어야 합니다. 인증은 이미 자체 필터가 처리하므로 그 의존성을 되돌리는 편이 맞습니다.

### `/actuator/gateway/routes` 가 404 입니다

**노출 목록에 넣는 것만으로는 켜지지 않습니다.** 대부분의 액추에이터 항목은 접근 권한이 기본으로 열려 있지만, 이 항목은 라우팅 구조가 통째로 드러나는 자리라 예외입니다. 권한을 주지 않으면 **등록조차 되지 않아** `/actuator` 목록에도 나타나지 않습니다.

기동 로그의 아래 줄이 판별 지문입니다. **5가 아니라 4라면 이것입니다.**

```
Exposing 5 endpoints beneath base path '/actuator'
```

`config` 저장소의 `gateway-server.yml` 에 아래가 있는지 확인합니다.

```yaml
management:
  endpoint:
    gateway:
      access: unrestricted
```

**`/actuator/refresh` 로는 반영되지 않습니다.** 항목의 등록은 기동할 때 일어나므로 다시 띄워야 합니다.

### 토큰이 있는데 401 이 납니다

로그의 내용에 따라 원인이 갈립니다.

| 로그 | 원인 |
|---|---|
| `토큰 쿠키가 없습니다` | 쿠키 이름이 `access_token` 인지, 브라우저가 쿠키를 보내고 있는지 |
| `토큰 검증에 실패했습니다` | 서명 불일치 · 만료 · 형식 오류. 아래 3-5 참고 |
| `토큰에 필요한 값이 없습니다` | **인증 서비스가 넣는 이름과 설정의 이름이 다릅니다** |

마지막 경우는 서명은 맞는데 안에서 값을 못 찾은 것입니다. `app.jwt.claim` 의 두 값과 인증 서비스가 토큰에 넣는 이름이 같아야 합니다.

서명 불일치는 **공개키와 개인키가 짝이 아닐 때** 납니다. 배포 환경에서 키를 새로 만들었다면 이 저장소가 아니라 `config` 저장소의 공개키도 함께 바꾸었는지 확인합니다.

### 기동에 실패하며 서블릿 관련 오류가 납니다

서블릿 스타터가 클래스패스에 끼어든 것입니다. 이 서비스는 WebFlux 로 돌기 때문에 둘이 함께 있으면 기동하지 못합니다.

```powershell
.\gradlew dependencies --configuration runtimeClasspath | findstr webmvc
```

의존성을 새로 추가한 뒤 이 오류가 났다면 그것이 서블릿 스타터를 데려온 것입니다.

### 대상 서비스가 있는데 503 이 납니다

유레카에 등록되었는지 먼저 봅니다.

```
http://localhost:8761
```

목록에 있는데도 503 이라면 등록된 주소가 이 서비스에서 도달 가능한 주소인지 확인합니다. 개발 도구에서 띄운 서비스가 `localhost` 로 등록되면, 컨테이너 안에서 도는 게이트웨이는 그 주소를 자기 자신으로 해석합니다.

### 설정을 바꿨는데 반영되지 않습니다

`config` 저장소에 커밋했는지 먼저 확인합니다. 설정 서버는 작업 디렉터리가 아니라 저장소를 읽습니다.

커밋했다면 이 서비스가 설정을 다시 읽지 않은 것입니다.

```powershell
curl.exe -X POST http://localhost:8080/actuator/refresh
```

---

## 7. 디렉터리 구조

```
gateway-server/
├── src/main/java/com/pawtrail/gatewayserver/
│   ├── GatewayServerApplication.java
│   ├── config/
│   │   ├── JwtProperties.java           공개키와 토큰 항목 이름
│   │   ├── GatewayAuthProperties.java   인증 없이 여는 경로 목록
│   │   └── JwtDecoderConfig.java        토큰 검증기, RS256 고정
│   ├── filter/
│   │   ├── AuthenticationFilter.java    ⓐ~ⓖ 를 순서대로 처리
│   │   └── GatewayErrorHandler.java     404 · 503 을 응답 규약에 맞춤
│   └── response/
│       ├── GatewayApiResponse.java      {code, message, data, traceId}
│       ├── GatewayErrorCode.java        401 · 403 · 404 · 503 · 500
│       └── ErrorResponseWriter.java     응답을 직접 써 내려 줌
├── src/main/resources/
│   ├── application.yml                  세 줄 (이름 · 설정 서버 주소 · 기본 프로파일)
│   └── logback-spring.xml               콘솔과 Loki appender
├── src/test/java/com/pawtrail/gatewayserver/
│   └── GatewayServerApplicationTests.java
├── build.gradle
├── gradle.properties
├── settings.gradle
├── Dockerfile
├── Jenkinsfile
├── .gitattributes
├── .editorconfig
├── .gitignore
├── .coderabbit.yaml
└── .github/
    ├── ISSUE_TEMPLATE/issue_template.md
    └── pull_request_template.md
```

이 서비스의 설정은 별도 저장소에 있습니다.

```
paw-trail/config/
└── gateway-server.yml        포트 · 라우팅 규칙 · 공개키 · 인증 예외 목록
```

환경별로 갈리는 값이 없으므로 이 서비스에는 4계층 파일이 없습니다. 배포 환경에서 라우트가 달라지면 그때 `gateway-server-prod.yml` 을 만듭니다.
