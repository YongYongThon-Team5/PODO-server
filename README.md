# PODO-server

Java 21 · Spring Boot · PostgreSQL 17 · Flyway

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

컨테이너 실행은 DB 실행까지만 담당한다. Spring 시작 시 Flyway가 `src/main/resources/db/migration`의 미적용 SQL을 순서대로 적용한다. 첫 실행에는 `app` 스키마의 서비스 테이블 14개와 Flyway 이력 테이블이 생성된다. JPA는 `ddl-auto: validate`로 Entity와 DB 구조를 검사하며 테이블을 자동 변경하지 않는다.

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
