# PODO-server

Java 21 · Spring Boot · PostgreSQL 17 · Flyway

## 데모 구현 가이드

Figma 기준의 기존 파트 변경·보완 사항과 담당 구분은 [데모 구현 가이드](docs/demo/README.md)를 참고한다. 현재 지원 범위, 리뷰할 변경 제안, 구현 후 수용 기준을 구분해 기록한다.

## 로컬 실행

Java 21과 Docker Compose가 필요하다. 각자 컴퓨터에서 PostgreSQL 컨테이너를 실행하고, Git으로 같은 DB 구조를 공유한다.

1. 환경 설정 파일을 복사하고 `.env`의 로컬 DB 비밀번호를 정한다.

   ```sh
   cp .env.example .env
   ```

2. PostgreSQL을 실행한다.

   ```sh
   docker compose up -d --wait
   ```

3. 같은 터미널에서 환경변수를 읽고 Spring을 실행한다. Compose는 `.env`를 자동으로 읽지만 Spring은 자동으로 읽지 않으므로 아래 과정이 필요하다. IDE로 실행할 때도 같은 환경변수를 실행 설정에 넣는다.

   ```sh
   set -a
   source .env
   set +a
   ./gradlew bootRun
   ```

컨테이너 실행은 DB 실행까지만 담당한다. Spring 시작 시 Flyway가 `src/main/resources/db/migration`의 미적용 SQL을 순서대로 적용한다. 첫 실행에는 `app` 스키마의 서비스 테이블, 사진 삭제 대기열, Flyway 이력 테이블이 생성된다. JPA는 `ddl-auto: validate`로 Entity와 DB 구조를 검사하며 테이블을 자동 변경하지 않는다.

DB 접속 기본값은 `localhost:5432`, DB 이름·사용자는 `podo`다. 포트가 사용 중이면 `.env`의 `DB_PORT`를 바꾼다. 원격 DB가 필요하면 Spring의 `DB_URL`을 지정한다.

```sh
# PostgreSQL 접속
docker compose exec db sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"'

# 실행 중지: 저장한 DB 데이터는 볼륨에 유지된다.
docker compose down
```

이 Compose는 서비스 개발용으로, 기존 수집용 컨테이너·볼륨을 사용하지 않는다. 장소·분류·퀴즈 데이터는 초기 마이그레이션에 포함하지 않는다.

## DB 변경 규칙

- 최초 구조는 검토한 14개 테이블 ERD를 따른다. UUID 배열·사진 JSON과 서비스 검증 책임도 유지한다.
- 활성 방문은 계정당 1건, 현재 공개 퀴즈 세트는 장소당 1개가 되도록 부분 UNIQUE 인덱스를 적용한다.
- 적용한 마이그레이션은 수정하지 않고 `V2__변경내용.sql`처럼 다음 버전 파일을 추가한다.
- 각 담당자가 자기 영역의 Entity와 변경 SQL을 함께 작성하고, 버전 번호·외래 키 적용 순서는 A가 취합한다.
- 사진 소유권, 분류 ID, 공개 시 3문항·문항당 2힌트, 상태 전이와 원자적 완료 처리는 각 담당 서비스에서 검증한다.
- 비밀번호가 있는 `.env`와 개인 접속 설정은 커밋하지 않는다.

## 테스트

Docker를 실행한 상태에서 아래 명령을 사용한다. Testcontainers가 별도 PostgreSQL 컨테이너를 생성하므로 로컬 `.env`나 개발 DB의 데이터가 필요하지 않다.

```sh
./gradlew test
```

## 인증 설정과 사용

인증은 카카오 로그인, 서명된 CSRF, 서비스 토큰 발급·갱신, 로그아웃, 내 계정 조회를 제공한다. 아이 프로필과 사진 API는 아래 절을 참고한다.

카카오 로그인을 실제로 사용하려면 `.env`에 아래 값을 설정한다.

| 환경변수 | 내용 |
|---|---|
| `API_ORIGIN` | 프론트와 API가 함께 사용하는 HTTPS 출처. 경로나 마지막 `/` 없이 지정 |
| `KAKAO_ADMIN_KEY` | 회원탈퇴 연결 해제 전용 서버 Admin Key. 로그인 REST 키와 별도로 설정하고 클라이언트에 전달하지 않음 |
| `KAKAO_CLIENT_ID` | 카카오 앱의 REST API 키 |
| `KAKAO_CLIENT_SECRET` | 카카오 앱에서 Client Secret이 활성화되어 있으면 필수 |
| `KAKAO_REDIRECT_URI` | 카카오 앱에 등록한 콜백 주소. 기본 경로는 `/api/auth/callback/kakao` |
| `AUTH_SUCCESS_REDIRECT_URI` | 로그인 성공 후 이동할 고정 화면. `API_ORIGIN`과 같은 출처 |
| `AUTH_FAILURE_REDIRECT_URI` | 취소·오류 시 이동할 고정 화면. `API_ORIGIN`과 같은 출처 |
| `AUTH_CSRF_SIGNING_KEY` | `openssl rand -base64 32`로 생성한 환경별 서명 키 |

Spring의 기본 HTTP 서버 앞에 같은 출처의 HTTPS 프록시를 구성해야 브라우저에서 전체 로그인 흐름을 사용할 수 있다. `.env.example`의 `https://localhost:8443`은 설정 예시이며 이 저장소에서 HTTPS 프록시나 성공·오류 화면을 실행하지는 않는다. 설정을 바꾼 뒤에는 앞의 환경변수 로드 과정을 다시 수행한다. 인증 키가 없어도 DB와 공개 API 개발은 가능하며, 키가 필요한 인증 요청은 503으로 거절한다.

1. `GET /api/auth/login/kakao`로 이동한다.
2. 카카오 인증 후 콜백에서 로그인 세션과 Refresh 쿠키를 발급하고 고정 성공 화면으로 이동한다.
3. `GET /api/auth/csrf` 응답의 `csrfToken`을 읽는다.
4. `POST /api/auth/token`에 본문 없이 `X-CSRF-Token`을 보내 Access를 발급받는다. 브라우저는 쿠키도 함께 전송한다.
5. 개인 API에는 `Authorization: Bearer {accessToken}`을 보낸다. `GET /api/me`는 `{accountId}`를 반환한다.
6. 갱신은 같은 브라우저에서 한 번에 하나씩 수행한다. Refresh는 매번 바뀌며 로그인부터 14일인 만료는 연장되지 않는다.
7. 로그아웃은 유효한 Bearer와 현재 세션의 CSRF로 `POST /api/auth/logout`을 호출한다. Refresh 쿠키 없이도 가능하다.

Access는 JWT가 아닌 43자 불투명 난수이며 30분 동안 유효하다. Access는 클라이언트 메모리에 두고, Refresh·OAuth nonce·CSRF는 `HttpOnly; Secure; Path=/`인 `__Host-` 쿠키를 사용한다. 토큰 발급·로그아웃 POST는 정확한 `Origin`과 쿠키·헤더의 서명된 CSRF를 검증한다. CSRF는 로그인 콜백·로그아웃·서명 키 교체 후 다시 조회한다. 토큰·state·nonce는 DB에 SHA-256 해시만 저장한다. 만료·폐기된 세션과 연결 토큰, 만료된 로그인 요청은 24시간이 지난 뒤 시간 단위로 정리한다.

B·C의 Controller에서는 `@AuthenticationPrincipal AuthenticatedAccount`의 `accountId()`를 사용한다. 클라이언트가 보낸 계정 ID를 소유자로 사용하지 않으며, 각 서비스에서 프로필·방문 등 리소스 소유권을 추가 확인한다. 공개 API에도 무효 Bearer를 보내면 401로 거절한다.

테스트는 카카오 호출을 대체해 검증하므로 실제 키가 필요하지 않다. 실제 카카오 앱과 브라우저를 사용하는 검증은 HTTPS 주소·앱 키를 설정한 환경에서 수행한다.

## 회원탈퇴 서버 설정

상세 요청·오류·클라이언트 연동 조건은 [회원탈퇴 API 연동 가이드](docs/api/account-withdrawal.md)를 참조한다.

`DELETE /api/me`는 본문·쿼리 없이 Bearer, 정확한 `Origin`, 현재 세션의 CSRF 쿠키·`X-CSRF-Token`으로 요청한다. 유효한 Refresh 쿠키가 함께 오면 Bearer와 같은 세션이어야 한다. 접수 트랜잭션에서 계정을 차단하고 모든 기기의 세션·토큰을 폐기한다. 응답은 `202 {"status":"PROCESSING"}`, `Cache-Control: no-store`이며 Refresh·CSRF 쿠키를 만료시킨다. 이 응답은 삭제 접수를 뜻한다.

`KAKAO_ADMIN_KEY`가 없거나 탈퇴 설정이 유효하지 않으면 변경 전에 `503 SERVICE_UNAVAILABLE`로 거절한다. 기존 로그인·공개 API의 기동은 유지한다. 기본 설정은 HTTP 연결 5초·응답 15초, 작업 점유 5분, 확인 주기 5초다. `podo.auth.withdrawal`의 `connect-timeout`, `request-timeout`, `poll-interval`로 조정할 수 있으며, `lease-duration`은 현재 저장소 계약상 `5m`이어야 한다. `worker-enabled=false`는 자동 실행만 멈추고 영속 작업을 유지하므로 테스트·작업자 분리 시에만 사용한다.

작업자는 카카오 연결 해제 확인 → 개인 DB 정리 및 파일 큐 확보 → 파일 삭제 → 계정 최종 삭제 순으로 처리한다. 재시작·점유 만료 후 재개하며 실패 간격은 30초부터 두 배로 늘려 최대 30분이다. 인증·설정 오류도 작업을 버리지 않고 분류 코드와 횟수만 기록한다. 설정을 고치면 다음 재시도에서 이어간다. 소유자를 복원할 수 없는 기존 파일 큐가 남으면 계정을 보수적으로 유지하므로 해당 큐의 저장소 오류를 해결해야 한다.

탈퇴 처리 중 검증된 카카오 콜백은 고정 실패 URI에 `error=ACCOUNT_WITHDRAWING`을 붙여 반환한다. 이때 재동의로 카카오 연결이 다시 생길 수 있으므로 진행 중 점유를 무효화하고 연결 해제를 다시 요구한다. 모든 삭제가 끝난 뒤 같은 카카오 사용자로 로그인하면 새 UUID를 발급한다. 실환경 연결 해제는 삭제를 허용한 테스트 계정에서 별도로 검증해야 하며, 자동 테스트는 공급자 스텁과 로컬 PostgreSQL·파일만 사용한다.

## 프로필 선택 목록

`GET /api/profile-options`는 로그인 없이 관심사·책 장르·연령·색상과 사진 규격을 조회한다. 무효 Bearer가 전달되면 공통 인증 필터가 401로 거절한다.

- 관심사·책 장르는 `app.tags`의 `INTEREST`·`BOOK_GENRE`를 조회하며 `PURPOSE`는 포함하지 않는다. 코드를 기준으로 정렬하고 고정 UUID를 그대로 반환한다.
- 연령·색상은 `podo.profile.options.age-bands`·`colors` 설정에서 관리한다. 공통 필드는 `code`, `label`, `active`이며 색상에는 `hex`가 추가된다. 설정 목록의 순서를 유지한다.
- 비활성 항목도 과거 선택의 표시를 위해 반환한다. 신규 선택 가능 여부는 `active`로 구분한다.
- 색상은 프로필 생성의 필수 선택이므로 색상 목록이 없으면 `503 OPTIONS_NOT_CONFIGURED`다. 관심사·책 장르·연령은 빈 목록을 허용한다. 잘못된 코드·표시명·색상 HEX나 중복 설정 코드도 503으로 거절한다.
- 확정 정책은 같은 계정의 색상 중복 불가, JPEG/PNG/WebP, 최대 5MiB·16,777,216픽셀이다. 이 조회 API가 사진 검사나 프로필 저장을 수행하지는 않는다.

팀 개발 환경에서는 `SPRING_PROFILES_ACTIVE=dev`로 실행하면 [개발용 설정](src/main/resources/application-dev.yaml)의 임시 색상 7개를 사용할 수 있다. 운영 목록이 확정되면 실제 태그를 DB에 적재하고 연령·색상을 배포 환경의 설정에 넣는다. 새 테이블이나 마이그레이션은 추가하지 않는다.

## 아이 프로필

| 메서드·경로 | 동작 |
|---|---|
| `POST /api/child-profiles` | 아이 프로필 생성, 201 |
| `GET /api/child-profiles` | 본인 프로필 커서 목록, 200 |
| `PATCH /api/child-profiles/{profileId}` | 본인 프로필 수정, 200 |

세 API는 Bearer 인증이 필요하며 소유 계정은 인증 정보에서 가져온다. 클라이언트가 계정·ID·시각 등의 서버 필드를 보내면 거절한다. 타인 프로필·사진과 미존재 리소스는 동일한 404다.

생성 시 `nickname`, `colorCode`, `interestCategoryIds`, `bookGenreIds`가 필수다. 연령·사진은 생략하거나 null로 둘 수 있다. 수정은 한 필드 이상 필요하며 생략은 유지, 배열은 전체 교체, 빈 배열은 해제다. 연령·사진의 명시적 null도 연결 해제이며 별칭·색상·배열의 null은 거절한다.

신규 색상·연령·태그 선택은 활성 목록만 허용한다. 이미 선택한 비활성 항목은 유지할 수 있다. 색상 목록이 비어 있는 환경에서는 새로운 색상 선택이 필요한 생성·수정은 503이다. 목록 조회, 별칭 수정, 기존 값 유지·선택 해제는 운영 목록이 없어도 가능하다.

색상 중복은 같은 계정 안에서만 409로 차단한다. 쓰기는 계정 행을 먼저 잠그고, 같은 색상의 동시 생성도 한 건만 성공하게 한다. 사진은 본인 소유의 `imageId`로 연결하며 여러 프로필이 공유할 수 있다. 마지막 연결이 끊기면 `orphaned_at`을 기록한다.

목록은 `createdAt ASC, id ASC`로 정렬하고 기본 20개, 최대 100개다. `nextCursor`는 다음 요청의 `cursor`로 그대로 전달하며 다른 계정에서 사용할 수 없다. 마지막 페이지는 `nextCursor: null`이다. 이 커서는 조회 경계이며 소유권 판단에는 항상 인증 계정을 사용한다. 프로필 시각은 PostgreSQL과 동일한 마이크로초 정밀도로 저장한다.

## 프로필 사진

- `POST /api/profile-images`에 Bearer와 multipart `file`을 보내면 `201` 및 `imageId`, `contentType`, `sizeBytes`를 받는다. 프로필 생성·수정의 `profileImageId`에 이 ID를 전달한다.
- 사진은 JPEG, PNG, WebP를 받는다. 이미지의 가로와 세로는 각각 최대 16,384픽셀까지 허용한다. 방향을 보정하고 메타데이터를 제거해 저장한다. WebP는 JPEG 또는 PNG로 변환하며 응답의 `contentType`은 저장된 형식이다.
- `GET /api/profile-images/{imageId}`는 사진을 업로드한 계정에 이미지 바이트를 반환한다. 프론트는 Bearer를 포함해 요청하고 받은 이미지로 표시한다.
- 사진 파일은 `PODO_PROFILE_IMAGE_DIRECTORY`(로컬 기본값 `./var/profile-images`)에 저장한다. 최초 업로드 또는 마지막 연결 해제부터 24시간이 지난 사진은 시간 단위로 정리하고, 파일 삭제에 실패하면 재시도한다.
