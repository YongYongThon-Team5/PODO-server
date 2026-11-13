# Task 3 report — personal database and file cleanup

## Scope and implementation

- Base: `f3aa51e`, branch `feature/account-withdrawal`.
- New production file: `src/main/java/com/podo/server/auth/withdrawal/WithdrawalCleanup.java`.
- New tests: `src/test/java/com/podo/server/auth/withdrawal/WithdrawalCleanupIntegrationTests.java`.
- No frontend, endpoint, worker, deployed environment, real account, provider key, dependency, or migration changes. V5 remains unchanged; no V6 was necessary.
- Existing generation completion already locks the account and rejects non-ACTIVE accounts; failure completion only updates an existing observation. Integration tests confirm those guards prevent late completion/failure from recreating deleted rows. No existing observation production changes were required.

## Interfaces for Task 4

`prepare(UUID accountId, UUID leaseToken) -> void`

- Owns a new transaction. Locks account → withdrawal job → account visits in ID order → personal quiz sets in ID order → owned profile image metadata in ID order.
- Requires WITHDRAWING, matching lease token, expiry strictly later than injected `Clock.instant()`, and CLEANUP_PENDING. Reads lease validity after obtaining locks rather than relying on `WithdrawalRepository.stage`.
- An owned FILES_PENDING retry is a no-op; ACTIVE accounts, missing/replaced/expired leases, UNLINK_PENDING, or unrelated ownership throw a generic `IllegalStateException`.
- Collects both V3 first-image storage keys and all V4 image keys, plus all account-owned profile image keys. UNION and the existing key constraint deduplicate by storage kind + key.
- Saves file queue before deleting DB references in the same transaction. On global-key conflict with another job, throws and rolls the entire prepare back; retry after the first job releases its queue proceeds safely.
- Clears visit set references and progress fields together to satisfy the visit status CHECK (temporary CANCELLED state exists only inside this transaction). Removes owned answers/hints, all personal set versions/questions, image rows, observations, visits, children, owned image metadata, linked OAuth requests, tokens, then sessions.
- Tokens are deleted before sessions, matching retired-session cleanup. Public sets/sources/places and other accounts' rows are untouched. Foreign ownership corruption that blocks an FK rolls the entire transaction back rather than removing another account's references.
- Uses fenced `advance(CLEANUP_PENDING, FILES_PENDING, clock.instant())` at the end, so lease expiry while DB work is in progress rolls back all changes.

`deleteFiles(UUID accountId, UUID leaseToken) -> boolean`

- Suspends an ambient caller transaction with NOT_SUPPORTED. Uses short new transactions for ownership checks and result updates; physical storage deletion has no active DB transaction or held account/job lock.
- Returns false if account/job ownership, lease, or FILES_PENDING is lost before/between file operations or before completion. Returns true only for current ownership with no pending files.
- Canonical lowercase UUID storage keys are required before calling either storage. Invalid metadata throws generic `IllegalStateException` and stays pending; this compensates for existing ProfileImageStorage.delete silently ignoring malformed keys.
- Existing storage failures propagate and the current file remains pending. Missing files count as success. Retry can repeat a physical deletion safely.
- Rechecks all current profile metadata, or both observation tables via existing referencesFile, before physical deletion. Files still used elsewhere are retained and this withdrawing account's claim is marked done.
- After I/O, checks WITHDRAWING + matching unexpired lease again under account/job locks before marking done. A worker whose lease expires during storage I/O cannot acknowledge the result or finish the account; a later worker retries the absent file.

`finish(UUID accountId, UUID leaseToken) -> void`

- Owns a new transaction with account → job locks. Requires current unexpired ownership, FILES_PENDING, no pending files, and no remaining directly owned personal rows (visits, children, profile metadata, sessions, linked OAuth requests).
- Invalid/stale/incomplete completion throws generic `IllegalStateException`; Task 4 should re-read stage/lease, ignore lost ownership, and retry still-owned failures.
- Deletes completed file rows → job → WITHDRAWING account in one transaction. FK failure rolls back the whole finalization. No tombstone remains; tests confirm the same synthetic Kakao subject may then obtain a new account UUID.

## TDD and verification

Environment: macOS, local JDK 21.0.13+9, Docker, actual PostgreSQL 17.11 Testcontainers. Storage checks use temporary local directories and real storage implementations. The profile storage spy only adds latch/transaction observation and delegates to the real delete; no storage success/failure response is mocked.

1. Added the five initial integration tests. First compilation identified the absent WithdrawalCleanup interface. Added a no-op interface skeleton to run real PostgreSQL assertions. `./gradlew test --tests '*WithdrawalCleanupIntegrationTests'` then failed all five tests on missing cleanup behavior (RED), with no database startup/fixture errors.
2. Implemented cleanup. Same five tests passed (GREEN).
3. Added an ambient-transaction I/O check. `fileIoSuspendsAnExistingCallerTransaction` failed because storage inherited the caller transaction (RED). Added NOT_SUPPORTED and verified it passed with the real delete (GREEN).
4. Added deterministic lease and DB-lock regressions, shared-job conflict recovery, and atomic FK rollback. Final cleanup suite: 11 tests, zero failures.
5. Combined targeted command passed: `./gradlew test --tests '*WithdrawalCleanupIntegrationTests' --tests '*ObservationIntegrationTests' --tests '*ProfileImage*Tests'` (at that point nine cleanup tests; two additional DB regressions were subsequently added and the full cleanup suite passed).
6. Final `JAVA_HOME=/Users/seokamoto/.local/share/podo-jdk21/jdk-21.0.13+9/Contents/Home ./gradlew test` executed once after all code/test changes: BUILD SUCCESSFUL, 54 seconds. Counts: 408 tests across 31 suites, zero failures, zero errors, zero skipped. `git diff --check` passed before staging.

The 11 cleanup tests cover:

- Full owned closure, two children sharing one profile image, legacy duplicate keys, another account sharing a physical profile file, common quiz answer preservation, multiple personal versions, FK cycles, V3 first images, V4 image lists, owned OAuth/auth cleanup, owned profile queue transfer, and unrelated queue/file preservation.
- Real filesystem deletion failure (nonempty directory), persistent pending state, account retained, and absent-file retry.
- Invalid key remains pending and blocks finish.
- ACTIVE/wrong stage/expired/replaced lease cannot authorize prepare/delete/finish.
- AI completion/failure after prepare cannot recreate rows.
- Storage I/O has no transaction even under an ambient caller transaction.
- Lease expiry/reclaim while a real storage call is blocked; old result cannot set done or finish.
- Account-lock wait followed by lease replacement; ownership is validated after acquiring the lock.
- Two withdrawing accounts sharing a storage key; second prepare rolls back until first finishes, then retries and deletes safely.
- Cross-account FK corruption rolls back queue, progress fields, answers, hints, questions, sets, and observations together.
- Retired-session token deletion overlaps prepare, proving matching token → session lock order completes without deadlock.

## Limits and concerns

- Existing `profile_image_deletions` has image_id/key/retry_at but no account_id. Once earlier orphan cleanup has removed the image metadata, account ownership cannot be recovered. This task transfers/removes existing queue rows only when their image_id still matches currently owned metadata, and captures all currently owned metadata keys. Metadata-free or unrelated queue entries remain untouched for the existing generic orphan worker; withdrawal cannot prove those historical files belong to this account or wait for them specifically. No unsafe inference from an unowned queue key was added.
- An in-progress filesystem delete cannot be cancelled when its lease expires. Immutable generated UUID keys, reference checks, idempotent deletion, and fenced DB acknowledgement protect the result; a reclaimed worker may repeat delete, and the stale worker cannot mark done/delete the account. Normal service writes never reuse a storage key. No guarantee is made for concurrent manual DB/storage mutation that introduces a reference to an old key after the reference check.
- Shared files remain while another account references them. Job-global key conflicts roll back/retry rather than losing ownership, and thus can wait until the earlier job finishes.
- Malformed legacy storage keys and inconsistent cross-owner FKs deliberately keep the withdrawal pending. They are not silently marked deleted.
- Tests cover local PostgreSQL/filesystem behavior and synthetic provider subjects only. Actual Kakao unlink, deployed storage/instances, external AI processing copies, backup retention, and log retention were not exercised.
