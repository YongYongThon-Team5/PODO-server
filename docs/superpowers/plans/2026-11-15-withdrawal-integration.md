# 회원탈퇴 PR 통합 검증 계획

> **For agentic workers:** Use superpowers:executing-plans for the integration and superpowers:requesting-code-review before merging.

**Goal:** PR #19에 최신 dev를 반영하고, 기존 도서 API와 탈퇴 API를 모두 검증한 뒤 병합·배포한다.

**Architecture:** 기존 탈퇴 구현과 비동기 정리 계약을 유지한다. 공유 설정·Swagger 테스트의 충돌을 합치고 실제 배포 설정과 공개 API를 확인한다.

**Tech Stack:** Java 21, Spring Boot, PostgreSQL Testcontainers, GitHub Actions, AWS Systems Manager.

**Spec:** [승인된 설계](../specs/2026-11-12-account-withdrawal-design.md), [연동 계약](../../api/account-withdrawal.md).

## Global Constraints

- 프론트 코드와 쿠키 정책은 변경하지 않는다.
- DELETE /api/me의 Bearer·Origin·세션 CSRF 검증, 202 접수 응답과 비동기 삭제를 유지한다.
- 실제 사용자 계정을 삭제하지 않는다. 외부 연결 해제는 스텁 테스트와 실제 설정 확인을 구별한다.
- API 키 값은 출력·커밋하지 않는다. 이미 적용된 마이그레이션은 수정하지 않는다.
- 사용자 승인 범위에 수정 커밋의 push, PR #19 병합과 배포 확인이 포함된다.

## Review Focus

- 도서 설정과 탈퇴 설정이 모두 Spring 설정에 남아야 한다.
- Swagger가 도서 조회와 탈퇴의 인증·응답 계약을 모두 노출해야 한다.
- 탈퇴의 동시 요청·지연 쓰기가 세션이나 개인 데이터를 복구하지 않아야 한다.
- 카카오 연결 해제 설정 누락을 API 사용 가능으로 보고하지 않는다.
- 재배포 후 관찰 저장 경로와 활성화·자동 공개 설정이 유지돼야 한다.

## Tasks

- [x] Task 1: 기존 탈퇴·공급자·Swagger 테스트를 실행하고 최신 origin/dev를 병합한다. 충돌 파일에서 두 기능을 모두 보존한다.
- [x] Task 2: 전체 `./gradlew --no-daemon test bootJar`와 독립 코드 리뷰를 수행한다. 새 결함이 발견되면 실패 재현 테스트 후 최소 수정한다.
- [ ] Task 3: 수정본을 PR #19에 push하고 현재 head의 CI를 확인한다. 충돌과 중요한 리뷰 문제가 없으면 병합하고 배포 성공을 확인한다.
- [ ] Task 4: 운영 설정의 키 등록 여부와 배포된 DELETE /api/me 계약을 계정 삭제 없이 확인한다. 실제 공급자 삭제 검증의 한계를 기록한다.

## 통합 중 확인한 사항

- 기존 탈퇴·공급자·Swagger 선행 테스트 통과(Java 21). 시스템 기본 Java 17 대신 기존 로컬 Temurin 21 경로를 사용한다.
- PR #20 이후 직접 충돌은 Swagger 테스트의 import와 컨트롤러 구성이다. 도서·탈퇴 테스트를 모두 보존한다.
- 독립 리뷰에서 재동의 후 기존 연결 해제 HTTP와 새 작업이 겹치는 경합을 발견했다. 두 회귀 테스트가 계정 조기 삭제로 실패하는 것을 확인한 뒤, 이전 UNLINK_PENDING 점유 만료까지 재시도를 보류하도록 최소 수정한다.
- 통합 후 전체 37 suites / 514 tests, 실패·오류·skip 0 및 bootJar 성공. 최종 독립 리뷰에서 병합을 막는 중요 문제가 남지 않았음을 확인했다.
