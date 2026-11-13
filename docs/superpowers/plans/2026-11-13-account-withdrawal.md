# 회원탈퇴 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Figma 마이페이지의 회원탈퇴를 연결하여 즉시 이용 차단 후 카카오 연결과 모든 개인 데이터를 영속 작업으로 삭제한다.

**Architecture:** 인증된 DELETE에서 계정을 WITHDRAWING으로 전환하고 모든 세션 폐기와 정리 작업 생성을 한 트랜잭션으로 처리한다. 스케줄 작업자가 카카오 unlink, 개인 DB 정리, 파일 삭제를 재시도하고 전 단계 완료 뒤 계정을 삭제한다. 프론트는 202를 접수로 안내한다.

**Tech Stack:** Java 21, Spring Boot, PostgreSQL/Flyway, RestClient, Testcontainers; 기존 React/TypeScript·React Query 프론트.

**Spec:** [승인된 회원탈퇴 설계](../specs/2026-11-12-account-withdrawal-design.md)

## Global Constraints

- 접수 즉시 이용 차단은 접수 트랜잭션 커밋 이후 새 인증·쓰기를 차단한다는 의미다.
- 모든 개인 기록·사진·개인 생성 퀴즈 삭제, 공용 장소·출처·공용 퀴즈와 타 계정 데이터 보존.
- 카카오 연결 해제와 파일 삭제가 끝나기 전 재가입 차단, 끝난 뒤 새 UUID로 가입.
- DELETE /api/me, 본문·쿼리 없음, 기존 Bearer·Origin·CSRF·세션 일치 검증. 202 {"status":"PROCESSING"}, no-store, Refresh·CSRF 쿠키 삭제.
- 외부 호출 중 DB 잠금 금지. 계정→세션 잠금 순서 준수. 실패한 작업을 완료처럼 처리하지 않음.
- 영속 재시도는 최대 간격 30분, 일시 실패 횟수 초과로 작업 유실 금지. 키 미설정은 접수 전 503.
- 새 의존성·관리자 API·상태 대시보드·알림·복구 기능을 추가하지 않음. API 키 원문을 로그·문서·프론트에 넣지 않음.
- 적용된 마이그레이션은 수정하지 않음. 구현 직전 dev의 최신 버전 확인 후 V5가 비어 있으면 V5 사용.
- 서버/클라이언트는 별도 브랜치·별도 PR. 본 계획은 회원탈퇴만 포함하며 도서·사진 퀴즈 연동은 후속 작업.

## Review Focus

- 외부 unlink 성공 직후 서버가 종료되는 경우: 중복 호출을 안전하게 판정하고 완료 상태를 복구한다(Task 2/4).
- 파일 삭제 큐의 메타데이터가 먼저 없어지는 경우: 작업 목록이 유실되지 않고 재가입은 계속 차단된다(Task 3/4).
- refresh가 늦게 반환되는 경우: 서버·프론트 모두 탈퇴 계정의 세션이 되살아나지 않는다(Task 1/5).
- AI 호출이 이미 진행 중인 경우: 외부 호출의 반환이 삭제된 관찰·퀴즈를 다시 만들지 않는다(Task 3/4).
- HTTP 응답이 유실되는 경우: 401을 탈퇴 완료라고 단정하지 않고 처리 중 재로그인으로 안내한다(Task 4/5).

## 파일과 인터페이스

서버 새 파일은 `src/main/java/com/podo/server/auth/withdrawal/` 아래에 모은다. `WithdrawalProperties`는 worker 간격·점유 5분·HTTP 연결 5초/응답 15초·Admin Key 설정을 관리하고 빈 키는 앱 전체 기동을 막지 않는다. 작업 주기는 5초, 첫 재시도 30초부터 증가해 최대 30분. 운영자 로그에는 오류 분류와 횟수만 남긴다.

- `WithdrawalRepository`: JDBC로 작업/파일 큐 점유·단계 전이. `claim(Instant now) -> Optional<Claim>`, `Claim(UUID accountId, UUID leaseToken)`. 만료 점유 회수와 토큰 일치 갱신.
- `WithdrawalService.request(UUID accountId) -> void`: 설정 검사, 계정 잠금, 차단·전 세션 폐기·작업 생성.
- `KakaoUnlinkClient.unlink(String subject) -> UnlinkResult`: `CONFIRMED`만 다음 단계 허용. 키·네트워크·판정 불가 실패는 분류 예외. subject는 서버 계정에서만 전달.
- `WithdrawalCleanup.prepare(UUID accountId, UUID leaseToken) -> void`: 파일 큐 확보와 개인 DB 참조 제거를 원자 처리. `deleteFiles(UUID accountId, UUID leaseToken) -> boolean`, `finish(UUID accountId, UUID leaseToken) -> void`.
- `WithdrawalWorker.runOnce() -> void`: 한 건 점유→현재 단계 실행→결과 기록. `Clock` 주입, 외부 호출은 트랜잭션 밖. 오래된 점유 결과는 반영하지 않음.
- Controller는 기존 `AuthController`에 DELETE를 추가해 private 인증 경계 helper를 재사용한다. 응답 record `WithdrawalResponse(String status)` 추가.
- 클라이언트 루트 `CLIENT`는 `/Users/seokamoto/Documents/잡동 프로젝트/용용톤/_workspace/kakao-login-review-20261111/PODO-client`로 식별한 저장소다. 실행 전 깨끗한 별도 브랜치에서 작업하며 다른 미완료 checkout은 수정하지 않는다.

---

### Task 1: 탈퇴 작업 저장과 인증 경합 차단

**Files:** 새 `V5__account_withdrawal.sql`(번호 재확인), `auth/withdrawal/WithdrawalRepository.java`; 수정 `auth/domain/Account.java`, `auth/repository/AccountRepository.java`, `AuthSessionRepository.java`, `AuthTokenRepository.java`, `auth/service/AuthSessionService.java`, `global/exception/ErrorCode.java`; 테스트 `auth/withdrawal/WithdrawalIntegrationTests.java`, 기존 `auth/AuthIntegrationTests.java`.

**Interfaces:** Account.beginWithdrawal(Instant now), AuthSessionService.revokeAllForAccount(UUID accountId), repository Claim/claim은 위 정의 사용. 계정은 마지막 삭제 전까지 kakao_subject UNIQUE 유지.

- [ ] 테스트 `withdrawalBlocksAllSessionsAndRejoin`: 접수용 서비스 동작을 repository/트랜잭션 수준으로 시험. ACTIVE→WITHDRAWING, 두 세션 모두 폐기, 작업 수 1, 로그인·refresh 실패를 assert한다. `concurrentRefreshAndWithdrawalCannotReviveSession`은 latch로 경합시키고 유효 세션 0·교착 없음 assert. 이 단계는 HTTP를 노출하지 않는다.
- [ ] `./gradlew test --tests '*WithdrawalIntegrationTests' --tests '*AuthIntegrationTests'` 실행하여 새 기능 부재로 실패하는지 확인한다. Docker/Java 오류는 기능 RED로 세지 않는다.
- [ ] DB에 account_withdrawals(account_id PK/FK, stage, lease_token, lease_expires_at, retry_at, attempt_count, error_code)와 account_withdrawal_files(account_id, storage_kind, storage_key, done; 저장소+키 유일)를 추가한다. 상태값·점유 일관성 CHECK, 조회 인덱스 포함. 계정/세션 순서로 로그인·refresh·logout 잠금 수정 및 잠금 후 ACTIVE 재확인. 기존 외부 공개 계약은 유지한다.
- [ ] 같은 테스트를 PASS 확인. 작업 점유 두 worker/만료 lease 토큰 무효화 테스트 추가해 PASS 확인. `git diff --check` 후 이 작업 파일만 커밋한다.

### Task 2: 카카오 unlink 공급자 경계

**Files:** 새 `auth/withdrawal/KakaoUnlinkClient.java`, `WithdrawalProperties.java`, `UnlinkResult.java`, `auth/withdrawal/KakaoUnlinkClientTest.java`; 수정 `src/main/resources/application.yaml`, `.env.example`, 필요 시 기존 config 등록.

**Interfaces:** unlink(String subject) -> UnlinkResult.CONFIRMED; 미설정은 ready=false(접수 전 503); 일시 오류는 재시도 가능한 분류 예외. 키는 서버용 `KAKAO_ADMIN_KEY`를 별도 설정에 바인딩.

- [ ] HTTP 스텁 테스트 `unlinkUsesAdminKeyAndServerSubject`에서 POST /v1/user/unlink, KakaoAK 인증, target_id_type=user_id, target_id 정확한 값·응답 id 일치를 assert. timeout/429/5xx/401/잘못된 id/깨진 JSON은 CONFIRMED가 아님을 assert. test 출력에 실제 키는 쓰지 않는다.
- [ ] `./gradlew test --tests '*KakaoUnlinkClientTest'` RED 확인.
- [ ] 공식 카카오 연결 해제·사용자 조회 명세에서 이미 해제된 사용자 판정의 오류 의미를 확인하고 테스트 fixture·코드 주석에 출처를 남긴다. Admin Key의 사용자 조회로 소속 여부를 확인하는 복구가 필요하면 같은 client에 한정한다. 확인된 미연결 상태만 성공 처리하고 auth 오류를 성공으로 삼지 않는다. 기존 RestClient/MockRestServiceServer 패턴 사용, 응답 오류에 키·원문 request URL 노출 금지.
- [ ] 동일 테스트와 기존 `KakaoClientTest` PASS 확인. 잘못된 설정이 있어도 기존 로그인 앱 기동을 막지 않는 테스트 포함 후 커밋한다.

### Task 3: 개인 DB·파일 정리

**Files:** 새 `auth/withdrawal/WithdrawalCleanup.java`, `auth/withdrawal/WithdrawalCleanupIntegrationTests.java`; 기존 `profile/image/cleanup/ProfileImageCleanup.java`, `observation/service/ObservationGenerationTransactions.java`, `observation/repository/ObservationJobRepository.java`는 경합 테스트에서 필요한 최소 보완만.

**Interfaces:** prepare/deleteFiles/finish는 위 정의 사용. ProfileImageStorage.delete, ObservationStorage.delete 재사용. 삭제 큐는 account_withdrawal_files에서 소유하여 기존 고아 정리 완료 여부에 의존하지 않는다.

- [ ] fixture에 계정 2개·공용 세트·개인 세트 여러 버전·답안/힌트·공유 프로필 사진·관찰 V3 첫사진/V4 목록을 넣는다. `cleanupDeletesOnlyOwnedDataAndPersistsFiles`에서 중복 키는 한 작업, 공용/타 계정 원본 행 보존, 개인 FK 연결 제거를 assert. `failedFileDeleteBlocksFinish`에서 큐 잔존과 재시도 후 전 삭제를 assert.
- [ ] `./gradlew test --tests '*WithdrawalCleanupIntegrationTests'` RED 확인.
- [ ] 계정→방문→관련 세트의 일관된 잠금과 소유 범위를 적용한다. 파일 키 영속화→visits.quiz_set_id 해제→해당 방문 답안·힌트→개인 문항/세트→이미지/관찰→방문→프로필/사진 메타→OAuth/토큰/세션 순으로 처리한다. 파일 없음 성공, 잘못된 저장소 키는 삭제 완료로 숨기지 않는다. 전 단계 확인 뒤 계정·큐·작업을 최종 트랜잭션에서 삭제한다.
- [ ] AI 결과 지연 fixture에서 prepare 이후 complete/failed가 삭제행을 재생성하지 않음을 assert한다. 필요하면 기존 worker에 최소 수정한다. 해당 테스트, 기존 `ObservationIntegrationTests`와 프로필 이미지 정리 테스트를 PASS 확인 후 커밋한다.

### Task 4: 탈퇴 접수 API와 작업자 연결

**Files:** 새 `auth/withdrawal/WithdrawalService.java`, `WithdrawalWorker.java`, `WithdrawalWorkerTest.java`; 수정 `auth/web/AuthController.java`, `auth/service/AuthSessionService.java`, `global/exception/ErrorCode.java`, `auth/withdrawal/WithdrawalIntegrationTests.java`, `global/config/SwaggerConfigurationTests.java`, README.

**Interfaces:** request(accountId), runOnce(), response status PROCESSING. OAuth 실패는 고정 `ACCOUNT_WITHDRAWING` 코드로 기존 failure redirect를 사용한다. 필요한 상태 판정은 카카오 subject를 클라이언트에서 받지 않는다.

- [ ] MockMvc로 설계 W-01~03의 202/body/cookie/no-store와 인증·Origin·CSRF·본문/쿼리 거절을 assert한다. 설정 없으면 503이며 계정 ACTIVE·작업 0. 일반 503과 WITHDRAWING 실패 콜백은 고정 안전 코드, 임의 redirect 없음을 assert.
- [ ] worker 테스트에서 unlink 성공→prepare→파일→finish 순서, 외부 호출 시 활성 DB 트랜잭션 없음, 고정 Clock으로 30초~30분 재시도, 종료/재기동·lease 만료 복구, 구 worker 완료 반영 거부를 assert. unlink 성공 직후 기록 실패 시 재실행해 안전하게 완료되는 스텁 포함.
- [ ] 테스트 RED 후 service/controller/worker를 연결한다. 한 트랜잭션에서 차단·전 세션 폐기·작업 생성. 재시도 횟수로 영구 유실하지 않고 비밀값 없는 분류 오류만 남긴다. Swagger에 202/401/403/503과 접수 의미를 문서화한다.
- [ ] 관련 테스트 PASS 후 전체 `./gradlew test` 실행. `./gradlew test --tests '*SwaggerConfigurationTests'`가 새 응답·schema를 포함하는지 확인. 자기 데이터 전 삭제·공용 보존·완료 후 새 UUID 재가입까지 한 통합 테스트로 확인하고 커밋한다.

### Task 5: Figma 탈퇴 버튼 연결과 데모 검증

**Files (CLIENT):** 수정 `src/api/auth.ts`, `src/api/queries.ts`(필요한 hook만), `src/pages/MyPage.tsx`, `src/pages/Login.tsx`, `src/pages/AuthCallback.tsx`, `src/api/schema.d.ts`, `docs/openapi.json`, `docs/api-integration.md`. 새 테스트 프레임워크를 설치하지 않는다.

**Interfaces:** auth.withdrawAccount(): Promise<void>는 202를 검증한 뒤 세션·CSRF를 정리한다. 기존 clearSession 경로에 session generation을 두어 접수 이전에 시작한 refresh 응답이 나중에 도착해 토큰을 복구하지 못하게 한다. 기존 로그인 메시지로 PROCESSING/ACCOUNT_WITHDRAWING을 표시한다.

- [ ] 브라우저 스텁으로 탈퇴 202/503/401/응답 유실 및 지연 refresh 케이스를 먼저 재현하고 현재 버튼/처리 부재를 확인한다. 네트워크 요청은 승인된 테스트 환경 또는 로컬 스텁만 사용한다.
- [ ] 기존 마이페이지의 탈퇴 UI를 연결한다. Figma 밖 새 화면을 만들지 않고 취소 시 요청 0, 중복 클릭 시 요청 1을 보장한다. 202 이후 개인 Query cache와 이미지 object URL 정리, signedOut 전환, 접수 문구 표시. 503/네트워크 실패는 완료 안내 금지. 진행 중 callback 메시지와 기존 로그인 실패 메시지를 구분한다.
- [ ] 로컬 서버 OpenAPI로 `npm run api:pull` 및 `npm run api:types` 실행 후 생성된 계약 diff를 확인한다. `npm run build` PASS. 스텁 재현에서 token/cache 부활 없음, 타 계정 새 로그인 시 이전 사진/기록 없음 확인 후 frontend 커밋한다.
- [ ] 서버 전체 테스트 PASS와 프론트 build PASS를 기록한다. 실제 키·unlink 권한이 준비된 경우에만 삭제를 허용한 테스트 계정으로 접수→전 기기 차단→정리→재가입을 확인한다. 키가 없으면 실환경 미검증이라고 남기고 성공을 주장하지 않는다.
- [ ] fresh reviewer가 서버/클라이언트 diff와 승인 설계의 범위·삭제·인증·재시도를 대조한다. 중요 지적 수정 후 서버/클라이언트 PR을 각각 dev/main 기준으로 생성한다. 실제 base는 당시 원격 정책을 확인하며 자동 병합·배포하지 않는다.

## 자체 검토

설계 1~3(API·화면)는 Task 4/5, 4(경합)는 Task 1/3, 5~6(영속 작업·삭제)는 Task 1/3/4, 7(Kakao)은 Task 2, 8(W-01~14)는 Task 1~5에 대응한다. 외부 완료/로컬 기록 사이 장애, 늦은 refresh, AI 결과 지연, 파일 큐 유실, 응답 유실을 각 담당 테스트에 포함했다. 실제 API 키 준비는 외부 의존성이며 스텁 검증과 구분한다.
