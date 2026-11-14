# C파트 서버 보완과 프론트 연결

## 적용 범위

| 항목 | 처리 |
|---|---|
| C-01 최신 완료 기록 | 기존 방문 목록에 완료일순 선택 추가. 기본 생성일순과 기존 커서는 유지 |
| C-01 카드 제목 | 기존 응답 유지. 대표 활동/퀴즈 제목, 방문별/장소별 카드 정책 확정 후 필드 확장 여부 결정 |
| C-02 미션 정보 | `observationGuide`, `position`, `answeredCount`, `viewedHintSteps` 재사용. 별도 제목을 임의로 생성하지 않음 |
| C-03 난이도 | 현재 공통 쉬운 퀴즈 유지. 연령별 생성·고학년 선택 계약은 제품 기준 확정 후 구현 |
| C-04 오답 연습 | 첫 답·점수 고정 계약 유지. 재접속 시 안내·응답·힌트 유지와 다른 답 재제출 차단 검증 |
| C-05 사진 퀴즈 | 자동 공개·시작·완료 기록 연결 및 HTTP 재시도 회귀 테스트 추가. Swagger 상태·재시도 의미 보완 |

이번 변경에 DB 컬럼이나 기존 Flyway 파일 변경은 없다. 프론트 연결, 운영환경 활성화·자동 공개·실제 Gemini 호출 확인은 SeokH-dev 담당이다.

## 최신 완료 방문 선택

완료 지도 API `/api/me/completed-places`는 장소별 집계이며 이미 `lastCompletedAt`순이다. 이 응답의 항목을 방문 한 건으로 간주하지 않는다.

선택한 장소의 가장 최근 완료 방문은 다음 요청으로 찾는다.

```http
GET /api/visits?status=COMPLETED&placeId={placeId}&sort=COMPLETED_AT_DESC&limit=1
Authorization: Bearer {accessToken}
```

- `items[0].id`가 실제 방문 ID다. 해당 ID로 기존 방문 상세·퀴즈 조회를 사용한다.
- 결과가 비어 있으면 해당 계정·장소의 완료 방문이 없는 상태로 처리한다.
- 정렬은 `completedAt DESC, id DESC`. 완료 시각이 같아도 PostgreSQL UUID 정렬로 순서를 고정한다.
- `sort=COMPLETED_AT_DESC`는 `status=COMPLETED` 단독 필터에서만 허용한다. 상태 생략·다른 상태·혼합 상태는 `400 INVALID_REQUEST`다.
- `sort`를 생략하거나 `CREATED_AT_DESC`로 보내면 기존 `createdAt DESC, id DESC` 동작이다.
- 다음 페이지에는 같은 상태·장소·정렬과 `nextCursor`를 전달한다. 필터·정렬 변경 시 커서 없이 새로 조회한다.
- 이전 생성일순 커서는 계속 유효하다. 생성일순과 완료일순 커서를 서로 재사용하면 `400 INVALID_CURSOR`다.

빈 목록은 기존과 같은 응답이다.

```json
{
  "items": [],
  "nextCursor": null
}
```

## 미션 화면과 기록 다시보기

- `GET /api/visits/{visitId}/quiz`의 `questions[].position`은 문항 순서다.
- `questions[].observationGuide`는 저장된 관찰 안내이며, 별도의 미션명이나 실제 방문 위치 인증 결과가 아니다. 값이 없을 때 다른 장소의 안내판·전시관 문구로 채우지 않는다.
- 진행 수는 `answeredCount / 3`이며 `correctCount / 3`과 다르다.
- 미열람 힌트 본문은 `PUT /api/visits/{visitId}/hint-views/{questionId}/{step}`으로 조회한다.
- `hintUsageCount`는 현재 세트의 고유한 문항·힌트 단계 열람 수다. 같은 단계를 여러 번 눌러도 증가하지 않는다.
- 재접속·기록 다시보기에는 서버에 저장된 `submittedAnswer`, `feedback`, `viewedHintSteps`를 사용한다.
- 첫 답과 다른 답을 재제출하면 `409 ANSWER_ALREADY_SUBMITTED`다. 같은 답 재전송은 중복 채점하지 않는다.
- 오답 연습은 화면 안에서 처리하며 저장된 첫 답·점수를 바꾸지 않는다. `/quiz/restart`를 연습용으로 사용하지 않는다.
- 세 문항 모두 응답하면 정답 수와 관계없이 완료된다.

## 사진 생성·시작·재접속

1. 본인 `PLANNED` 방문에 원본 파일을 `files`로 1~3장 전송한다. 같은 파일 묶음 재전송은 같은 `Idempotency-Key`를 사용한다.
2. `GET /api/visits/{visitId}/observations/latest`로 생성 상태를 복원한다. `READY`만 확인하지 말고 `startAvailable=true`를 확인한다.
3. `POST /api/visits/{visitId}/report` 후 기존 퀴즈 API로 진행한다.
4. 이미 방문 상태가 `QUIZ_IN_PROGRESS`이면 기존 퀴즈를 복원한다. `startAvailable`은 새 시작 가능 여부이므로 진행 중·완료 방문에서는 false다.
5. 완료한 방문은 실제 방문 ID로 다시 조회한다. 새 사진 업로드나 퀴즈 재생성으로 기록을 대체하지 않는다.

개인 사진의 `contentUrl`은 Bearer 인증이 필요한 상대 경로다. 인증 fetch 결과를 화면에 표시한다. 상태 응답에는 정답·해설·미열람 힌트가 포함되지 않는다.

### 실패 재시도

```http
POST /api/visits/{visitId}/observations/{observationId}/retry
Authorization: Bearer {accessToken}
Content-Type: application/json

{"expectedAttempt": 1}
```

- `retryAvailable=true`일 때 마지막으로 조회한 `attemptCount`를 전송한다.
- 같은 실패에 대한 중복 요청은 재작업을 중복 예약하지 않는다. 작업자가 점유할 때 시도 횟수가 증가한다.
- 이전 시도의 지연 요청은 현재 상태를 반환한다. 예를 들어 현재 실패가 2차인데 `expectedAttempt=1`이면 2차 실패를 재시도하지 않는다. 응답을 읽고 화면 상태를 갱신한다.
- 최대 3회 점유 후 실패하거나 `INSUFFICIENT_EVIDENCE` 등 재시도 불가 사유면 재시도 버튼을 표시하지 않는다.
- 새 사진 안내 등 실패별 UX는 프론트 담당이 연결한다. 최신 사진 작업 실패 후 공용 퀴즈로 자동 전환하는 정책은 추가하지 않았다.

### 공개 설정

기본 `PODO_OBSERVATION_AUTO_PUBLISH=false`에서는 생성 완료 후에도 DRAFT여서 `startAvailable=false`일 수 있다. `true`에서는 기존 출처·응답 검사 통과 결과가 `AUTOMATED` 검수 방식으로 공개된다. 자동 검사는 사진 촬영 장소나 사실의 진위 전체를 인증하지 않는다.

실제 배포 설정과 모델 키를 변경하지 않았다. 서버 통합 테스트는 테스트용 자료와 생성 출력을 사용하며 실제 Gemini 호출을 대신하지 않는다.

## 남은 제품 결정

- 카드 제목은 장소 대표 활동인지 완료 퀴즈의 별도 제목인지, 카드와 날짜는 장소 단위인지 방문 단위인지 확정이 필요하다.
- 와이어프레임의 프로필 연령대는 4~5세·6~7세·8~9세다. 이 선택을 생성 난이도에 자동 적용할지, 별도 고학년 선택을 제공할지는 별도 결정이다. 운영 코드·라벨 매핑도 확인해야 한다.
- 위 미정 사항에 새 응답 필드나 DB 정책을 임의로 추가하지 않았다.

## 회귀 검증 명령

Docker 실행 후 Java 21 환경에서 실행한다.

```bash
./gradlew test \
  --tests 'com.podo.server.visit.VisitIntegrationTests' \
  --tests 'com.podo.server.visit.CompletedPlaceIntegrationTests' \
  --tests 'com.podo.server.quiz.QuizIntegrationTests' \
  --tests 'com.podo.server.observation.ObservationIntegrationTests' \
  --tests 'com.podo.server.observation.ObservationAutoPublishIntegrationTests'
```
