# 관찰 사진 업로드 계약

`POST /api/visits/{visitId}/observations`는 본인 방문에 사진을 업로드하고 생성 작업을 예약한다. 성공 응답은 `202 Accepted`와 관찰 상태(`id`, `visitId`, `generationStatus`, `images` 등)다. 업로드 성공이 퀴즈 생성 완료를 뜻하지는 않는다.

## 요청

- `Authorization: Bearer {accessToken}`이 필요하다.
- `Idempotency-Key`는 필수이며 영문·숫자·밑줄·하이픈으로 된 8~128자 문자열이다.
- 본문은 `multipart/form-data`다. 현재 프론트가 사용하는 `images` 또는 기존 API의 `files` 중 **한 이름만** 선택해 같은 이름으로 파일 1~3개를 반복 전송한다. `images[]`나 `files[]`는 지원하지 않는다.
- 파일당 최대 5MiB(5,242,880바이트)이며 JPEG·PNG·WebP 이미지 내용 검사는 기존 사진 처리 규칙을 따른다. 확장자만 바꾼 파일이나 빈 파일은 허용하지 않는다.
- 두 이름을 혼합하거나 다른 파일 필드, 텍스트 필드, 쿼리 파라미터를 추가하면 `400 INVALID_REQUEST`다. 파일 0개 또는 4개 이상도 동일하다. 크기 초과는 `413 UPLOAD_TOO_LARGE`, 지원하지 않는 이미지 내용은 `415 UNSUPPORTED_IMAGE_TYPE`이다.

## 브라우저 예시

아래는 문서 예시이며 프론트 구현 변경을 포함하지 않는다. `photos`는 사용자가 선택한 `File` 배열, `accessToken`은 발급받은 Access 토큰이다.

```javascript
const form = new FormData();
for (const photo of photos) {
  form.append('images', photo); // 기존 클라이언트는 'files'로 동일하게 전송 가능
}

const response = await fetch(`/api/visits/${visitId}/observations`, {
  method: 'POST',
  headers: {
    Authorization: `Bearer ${accessToken}`,
    'Idempotency-Key': requestKey,
  },
  body: form,
});
```

`Content-Type`은 직접 설정하지 않는다. 브라우저가 multipart boundary를 포함해 만든다. 텍스트로 변환한 파일명·경로·base64가 아닌 `File` 바이트를 전송한다.

## 재전송과 상태 조회

새 업로드에는 새 요청 키를 만든다(예: `crypto.randomUUID()`). 네트워크 오류 후 같은 요청을 재전송할 때에는 같은 키와 같은 순서의 파일 바이트·각 파일의 Content-Type을 유지한다. 같은 방문의 같은 키라면 `images`에서 `files`로 이름을 바꿔도 기존 관찰 ID와 `202`를 반환한다. 파일명은 중복 판단에 포함하지 않는다. 같은 키로 파일 수·순서·바이트·Content-Type을 바꾸면 `409 IDEMPOTENCY_KEY_REUSED`다.

응답의 관찰 ID로 `GET /api/visits/{visitId}/observations/{observationId}`를 조회하거나 `/latest`로 최신 상태를 조회한다. 조회에도 본인의 Bearer가 필요하다. 퀴즈 시작 가능 여부는 응답의 `startAvailable`로 확인한다.

Swagger의 `uploadObservation` 요청 본문은 `files`와 `images`의 배타적 `oneOf`다. 각 대안은 필수 binary 배열 1~3개만 허용하고 추가 속성을 허용하지 않는다. 이 수정은 별칭 정규화에만 적용되며 생성 모델·상태 전이·저장·소유권 규칙은 기존 서비스를 그대로 사용한다.
