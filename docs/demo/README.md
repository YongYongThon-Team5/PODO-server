# Figma 데모 구현 가이드

작성일: 2026-11-12 · 비교 기준: 서버 `00297d0`, 클라이언트 `486bd4b`

이 문서는 **기존 담당 기능의 변경·보완을 리뷰하기 위한 가이드**다. 구현 완료나 새 API 계약의 확정을 의미하지 않는다. Figma의 화면과 사용자 행동을 기준으로 현재 서버의 지원 범위, 차이, 제안, 수용 기준을 구분한다.

## 기준과 읽는 순서

1. [Figma wireframe](https://www.figma.com/design/lWZGGaQay5Wcp7wKw6YEze/?node-id=143-1611)을 제품 기준으로 본다. 이전 PRD와 현재 코드가 디자인과 다르면 검토할 변경으로 기록한다. 오래된 와이어프레임·보류 화면은 자동으로 구현 범위에 포함하지 않는다.
2. [담당 및 제외 범위](ownership.md)에서 작업 주체를 확인한다. 이번 논의에서 진행한 작업과 담당이 애매한 항목은 `SeokH-dev`가 맡는다.
3. [B: 장소·추천·인기](b-place-recommendation.md), [C: 방문·퀴즈·기록](c-visit-quiz-records.md)에서 본인 영역을 검토한다.
4. 리뷰에서 계약과 데이터 의미를 정한 뒤 구현 PR을 만든다. 제안 필드명·경로는 리뷰 전 계약으로 사용하지 않는다.

## 이번 논의의 확정 경계

- Figma에 있는 기능을 구현한다. 프론트만의 임시 기능을 새 제품 요구로 승격하지 않는다.
- 차량 이동 시간과 위치 검증(방문 반경 및 사진 GPS)은 이번 데모에서 제외한다. 화면에 남은 인증·이동시간 문구도 함께 정리한다.
- 사진 퀴즈는 서버 자동 검사를 통과하면 바로 공개한다. 사람의 사전 승인 대기는 요구하지 않는다.
- Figma와 프론트에 없는 문제 신고와 별도 관리자 검수 API는 이번 범위에서 제외한다.
- 회원탈퇴와 도서 API는 사용자 담당의 별도 신규 기능이다. B·C의 미구현 항목으로 배정하지 않는다.
- 도서는 용인시 공식 API가 제공하고 실제로 확인 가능한 범위까지 구현한다. 검색 실패를 미소장, 불확실한 대출 정보를 현재 대출 가능으로 표현하지 않는다.

## 리뷰 완료 기준

각 항목에 대해 기존 API 재사용 / 필드 확장 / 데이터 보강 / 사용자 담당 검토 중 하나를 정하고, 요청·응답 예시와 빈값·오류·기존 기록 호환 동작을 합의한다. 확정되지 않은 사항은 질문으로 남기며 구현 완료로 표시하지 않는다.

문서 PR에서는 실행 코드·DB·배포 설정을 변경하지 않는다. 구현 PR에서는 관련 통합 테스트와 Swagger 스키마를 함께 갱신한다. 적용된 Flyway 파일을 수정하지 않고 새 버전을 추가하며 실제 비밀키·개인 사진·계정 정보를 문서에 넣지 않는다.

## 담당 근거

담당은 실명 추정이 아닌 기존 PR 작성 기록 기준이다.

- `Yejin111`: [#12 장소·추천·인기](https://github.com/YongYongThon-Team5/PODO-server/pull/12)
- `jinyoung02`: [#9 방문](https://github.com/YongYongThon-Team5/PODO-server/pull/9), [#10 퀴즈](https://github.com/YongYongThon-Team5/PODO-server/pull/10), [#11 완료 지도](https://github.com/YongYongThon-Team5/PODO-server/pull/11), [#13 사진 퀴즈](https://github.com/YongYongThon-Team5/PODO-server/pull/13)
- `SeokH-dev`: [#3 인증](https://github.com/YongYongThon-Team5/PODO-server/pull/3), [#5 프로필](https://github.com/YongYongThon-Team5/PODO-server/pull/5), 이번 논의의 신규·연동·애매한 작업
