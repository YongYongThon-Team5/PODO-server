# Place Books Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Figma 장소 상세의 검수된 관련 책과 실제 용인시 소장 도서관 조회 서버 API 제공.
**Architecture:** 설정 선정 목록→고정 공식 HTTP 공급자→정확한 서지 일치/도서관 집계→공개 장소 GET API. 새 DB 테이블 없이 기존 장소 저장소 사용.
**Tech Stack:** Java21, Spring Boot RestClient, PostgreSQL Testcontainers, Swagger. 새 의존성 없음.
**Spec:** [place books design](../specs/2026-11-14-place-books-design.md)

## Global Constraints

서버 전용, 프론트 금지. origin/dev 기반 feature/place-books. 실제 키·운영 도서 연결 추정 금지. 최대3선정권·각첫100행·최대3HTTP·연결2초/응답5초·자동재시도 없음. 대출 가능/반납예정/표지/ISBN 제공 금지. 미조회/실패/빈결과/조회절단을 구별. no-store, 공개 장소404/입력400/무효Bearer401. 외부HTTP에 DB tx 금지. 제공자 key/body/cause 로그/응답 노출 금지.

## Review Focus

- 제공자의 resultCode/resultcode·코드숫자/문자 차이는 공식 관측 두 형식만 허용(Task1).
- 공급자 빈결과와 장애를 혼동하지 않음(Task1/2).
- 같은 제목 다른 저자·판본·연도는 합치지 않음(Task2).
- 페이지100행 제한을 전체 소장 결과로 오해하지 않음(Task1/2).
- 설정 오류·키 부재가 다른 앱 기능을 중단하지 않음(Task1/2).

### Task 1: 공급자 클라이언트·선정 설정

Files: create `books/BookProperties.java`, `books/YonginLibraryClient.java`, `books/LibrarySearchResult.java`; tests `books/YonginLibraryClientTest.java`; config application.yaml/.env.example 및 기존 config 등록.
Interfaces: properties.catalog(UUID placeId)->List<CuratedBook> (title,author,publisher,publicationYear), configured(); client.search(CuratedBook)->LibrarySearchResult(rows,totalCount,truncated). 실패는 sanitized ProviderUnavailableException. 입력 인자와 응답 record는 books 패키지에서 공유한다.

- [x] HTTP 스텁 테스트를 먼저 작성. 고정 endpoint, serviceKey 인코딩, pageNo1,numOfRows100,title/author/publisher 전달, 실제 flat 성공·NODATA code3 변형,totalCount>100,truncated,잘못된 code·body·페이지·HTTP/timeout/key 없는 상황을 assert.
- [x] 집중 테스트 RED 확인. 누락 class 컴파일은 실제 assertion RED로 세지 않는다.
- [x] 기존 Kakao/Weather RestClient 관행으로 구현. 기본 empty catalog/key; 최대3,중복서지,빈 필드,잘못된 UUID/연도 설정을 조용히 운영 데이터로 사용하지 말고 해당 조회에서 준비안됨으로 처리. 고정 timeout과 키 redacted toString, 모든 외부 예외 sanitize.
- [x] 집중+기존 외부클라이언트 테스트 PASS. 전체 테스트 한 번, 변경 파일만 커밋하고 report 작성.

### Task 2: 장소 도서 API·서비스·Swagger

Files: create `books/PlaceBookService.java`, `books/PlaceBookController.java`, `books/PlaceBookResponse.java`; tests `books/PlaceBookServiceTest.java`, `books/PlaceBookIntegrationTests.java`; update SwaggerConfigurationTests. 기존 PlaceRepository 재사용, 기존 장소 응답 불필요 확장 금지.
Interfaces: service.get(UUID placeId)->PlaceBookResponse(items,warnings,checkedAt). Controller GET /api/places/{placeId}/books. warnings spec고정값; items각도서 title+libraries(중복 없는 도서관 이름 문자열 목록), 서지4개는 내부 비교에만 사용.

- [x] test-first: 정확한4필드일치/다른판본제외/중복도서관 이름/선정순서/부분실패/미선정/키부재/미발견/절단. 실제 PG MockMvc로 공개200/비공개404/잘못된UUID400/쿼리·본문400/무효Bearer401. HTTP가 ambient DB tx 없는지 assert.
- [x] RED 확인 후 최소 구현. 제공자 결과만 반환, 최대3,경고 중복제거 고정순서, no-store+X-Request-Id. 최대조회 범위를 초과한 설정은 명확히 거절/경고하며 임의 운영값 생성 금지.
- [x] Swagger response schemas/error코드와 기존 그룹을 검증. focused+전체PASS,커밋/report. Task1 인터페이스 변경 필요시 컨트롤러에게 보고.

### Task 3: 서버 연동 문서·전체 리뷰

Files: `docs/api/place-books.md`,README,설계/계획 상태 기록.
- [x] API request/response와 warnings, 실제지원필드, checkedAt의 의미, 공식신간범위·100행제한, 도서 미발견/공급자실패 구별,빈 catalog/key·정확한 선정값 설정방법·키변수이름만 문서화. 합성 예시를 운영값으로 쓰지 않도록 명시.
- [x] 문서 링크/비밀값/diff검사 및 실제 서버·Swagger 대조. 수정 없는 실행코드는 suite반복 대신 직전 결과 XML 확인.
- [ ] 독립 전체 브랜치 리뷰·중요지적수정·최종테스트. 실제 키 없는 실환경 검증 미수행 기록. 브랜치 보존, 사용자 지시 없는 병합/배포 금지.

## 검증 상태

Task 1·2 구현 완료. Task 2 최종 집중 테스트 24건과 전체 테스트 395건 통과(실패·오류·건너뜀 0). 문서와 서버/Swagger 계약을 대조했다. 독립 전체 브랜치 리뷰는 진행 중이다. 실제 키를 사용한 신규 코드의 실시간 공급자 호출 및 배포 검증은 수행하지 않았다. 운영 키와 검수된 선정 목록은 별도 설정이 필요하다.
