# Swagger 사용 안내

서버를 실행한 뒤 같은 서버 주소에서 아래 경로에 접속합니다. 로컬 기본 포트 기준 Swagger UI 주소는 `http://localhost:8080/swagger-ui.html`입니다. 서버의 데이터베이스 등 실행 환경은 별도로 준비해야 합니다.

| 경로 | 용도 |
| --- | --- |
| `/swagger-ui.html` | Swagger UI 진입점 (`/swagger-ui/index.html`로 이동) |
| `/v3/api-docs/podo` | `/api/**` 컨트롤러만 포함하는 `podo` 그룹 OpenAPI JSON |
| `/v3/api-docs` | Springdoc 기본 OpenAPI JSON |
| `/v3/api-docs/swagger-config` | Swagger UI 설정 |

Swagger UI에서 `podo` 그룹을 선택해 사용합니다. 컨트롤러가 아직 구현되지 않은 API는 문서에 나타나지 않습니다. 그룹 밖의 핸들러는 `podo` 문서에서 제외되며 기본 문서의 범위는 Springdoc 기본 동작을 따릅니다. OpenAPI 서버 주소는 상대 경로 `/`이므로 **Try it out**은 문서를 연 서버의 동일한 origin으로 요청합니다. 이 설정은 origin 루트에 배포하는 현재 경로 구성을 기준으로 합니다.

## 인증 표시와 Authorize

`BearerAuth`는 opaque access token을 사용하는 HTTP Bearer 보안 스키마입니다. 실제 로그인으로 발급받은 access token이 있다면 **Authorize**에 `Bearer ` 접두사를 제외한 토큰 값만 입력합니다. Swagger UI가 `Authorization: Bearer <token>` 헤더를 구성합니다. 인증 값은 새로고침 후 유지하지 않으며 Petstore 기본 URL도 사용하지 않습니다.

`RefreshCookie`는 토큰 재발급 API용 `__Host-refresh` 쿠키 스키마입니다. HttpOnly 쿠키는 Swagger 입력으로 직접 설정할 수 없습니다. 정상 로그인으로 브라우저에 저장된 쿠키를 사용하며, 브라우저의 Secure·SameSite 등 쿠키 전송 조건도 충족해야 합니다.

인증 구현은 별도 작업입니다. 이 설정만으로 로그인이나 토큰 검증이 동작하지 않습니다. 문서 접근은 공개이고, 기존 `/api/**` 인증 규칙은 그대로 적용됩니다. `@SecurityRequirement`는 문서 표시용이며 실제 접근 제어는 `SecurityConfig`와 인증 구현이 담당합니다.

비공개 API에는 다음처럼 보안 요구사항을 명시합니다.

```java
@GetMapping("/api/example")
@SecurityRequirement(name = "BearerAuth")
public ExampleResponse example() {
    // ...
}
```

공개 API도 있으므로 전역 보안 요구사항은 설정하지 않습니다. 공개 메서드는 `@SecurityRequirement`를 생략합니다. 컨트롤러 클래스에 보안 요구사항을 붙였다면 공개 메서드에서 `@SecurityRequirements`로 요구사항을 비웁니다. 공개 여부는 반드시 `SecurityConfig`의 허용 경로와 함께 맞춰야 합니다.

## 설정 검증

`./gradlew test --tests com.podo.server.global.config.SwaggerConfigurationTests`로 그룹 필터, 보안 스키마, 상대 서버 주소, Swagger UI·설정 접근과 API 인증 규칙을 검증합니다. 이 테스트는 테스트 전용 컨트롤러와 데이터베이스 자동 설정을 제외한 컨텍스트를 사용하므로 데이터베이스가 필요하지 않습니다. 실제 로그인과 토큰 발급·검증은 이 테스트의 범위 밖입니다.
