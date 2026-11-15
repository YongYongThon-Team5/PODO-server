# 회원탈퇴 구현 검증 기록

2026-11-14 · 서버 전용 `feature/account-withdrawal` · 제품 코드 검증 커밋 `76e7cb4`

- 전체 `./gradlew test`: 33 suites, 428 tests, 실패·오류·skip 0, 58초. Java 21, PostgreSQL 17 Testcontainers.
- 최종 경합 수정 집중 테스트: 113 tests 통과. 실제 PostgreSQL 잠금 대기로 탈퇴 이후 프로필 사진/OAuth 연결의 늦은 쓰기를 재현하고 차단을 확인했다.
- 단계별 독립 리뷰와 전체 브랜치 리뷰 완료. 마지막 두 P2 지적은 수정 및 범위 재리뷰에서 해결됐다.
- 실행 코드·문서·마이그레이션은 서버 저장소에만 변경했다. 프론트 구현은 다른 담당자 영역으로 제외했다.
- 카카오 API는 HTTP 스텁으로 검증했다. 실제 Admin Key·삭제 허용 계정·배포 환경·기기는 검증하지 않았다.
- V6 이전의 소유자 불명 사진 삭제 대기열이 남으면 전체 탈퇴 완료가 보류될 수 있다. 실제 파일 삭제를 확인하지 않고 완료·재가입을 허용하지 않기 위한 보수적 정책이다.
- 현재 브랜치는 로컬 보존 상태다. 사용자 선택 전 병합·푸시·배포하지 않는다.

## 실행 중 판단 기록

아래는 진행 기록의 판단을 순서대로 보존한 것이다. 초기에 프론트 브랜치를 준비한 판단은 이후 사용자의 서버 전용 지시로 대체되었고 프론트 소스 수정은 하지 않았다. 테스트 대상/메서드명/설정 방식의 선택이 잘못됐을 경우 해당 회귀 테스트·계약 문서의 재수정이 필요하다. 잠금/파일 완료 장벽이 잘못되면 인증 또는 삭제 무결성에 영향이 있어 실제 DB 경합 테스트로 검증했다. 레거시 장벽은 무관한 오래된 파일 실패도 전체 완료를 지연시킬 수 있다.

- Ruling: Task 1 pre-HTTP tests may use direct repository operations to exercise account transition; request orchestration belongs to Task 4 — avoids premature service duplication — cost if wrong is test refactoring.
- Ruling: Native managed worktree created under ~/.codex/worktrees, feature branch; frontend will use a separate feature branch. No shared branch push/merge/deploy.
- Ruling: Account.beginWithdrawal() has no Instant parameter; auditing owns updatedAt, so avoid a no-op parameter or base entity modification. Plan signature updated at execution finish; cost is documentation correction only.
- Frontend drift: origin/main advanced to 09e8242; PR #6 ed6ee22 already added photo upload integration. Ruling: Task5 starts at latest main and only withdrawal; do not reimplement photo flow. Plan's earlier client 486bd4b observations are historical.
- Ruling: native client worktree creation hung with no result/operation ID for several minutes; terminated waiting tool and used git fallback after verifying worktree list contained no new client checkout. Frontend isolated at /Users/seokamoto/Documents/잡동 프로젝트/용용톤/_workspace/account-withdrawal-client-20261113 on feature/account-withdrawal from 09e8242. Parent of repo so no ignore edit needed.
- Task1 scope extension: implementer self-review found AuthCleanupService token→session delete vs revokeAll session→token update inversion. Ruling: fix now with focused real overlap regression, then full test — auth concurrency requirement encompasses existing cleanup; do not defer known deadlock.
- Task2 ruling: -101 is confirmed absent only for HTTP400 with integral numeric code -101 per official Kakao error contract. All other errors remain failures. Properties leaseDuration must remain 5m to match Task1 fixed lease (avoid silently configurable mismatch); invalid withdrawal settings disable ready(), not app boot. Worker later retains retry for all failures, even nontransient classification.
- Task3 fix round1 started: provenance missing in legacy profile queue violates all-photo completion. Ruling: V6 nullable owner + atomic future queue owner capture; owned orphan queue transfer; global legacy owner-null finalization barrier until generic drain. Safe no inference; liveness cost unrelated legacy invalid/undeletable row blocks completion until remedied. Explicit migration compatibility policy, not silently finished deletion. Reviewer supports; testing new queue failures and legacy barrier. Scratch report untracked in separate housekeeping commit, local remains.
- Task4 ruling: blocked validated Kakao login may reconnect app externally. Rearm unlink atomically under issueForKakaoSubject's account lock and invalidate old job lease, using narrow dedicated BusinessException subclass + noRollbackFor only that subclass. Then return fixed ACCOUNT_WITHDRAWING callback. Avoid separate post-rollback rearm gap where finish could win. Tests must prove rearm commits with no new session and other errors still rollback.
