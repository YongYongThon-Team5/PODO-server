# PODO 개발 서버 배포

서울 리전의 `podo-dev` 스택은 EC2 `t4g.small` 한 대에서 Spring Boot와 PostgreSQL 17을 실행한다. Caddy가 고정 IP의 HTTPS 인증서를 자동으로 갱신한다. 외부에는 HTTPS와 인증서 검증·리디렉션용 HTTP 포트만 열고, 서버 관리에는 AWS Systems Manager를 사용한다.

## 처음 연결할 때

1. [aws-dev.yaml](./aws-dev.yaml)로 서울 리전에 CloudFormation 스택을 생성한다. `GitHubDevSubject`에는 GitHub API `GET /repos/YongYongThon-Team5/PODO-server/actions/oidc/customization/sub`가 반환한 `sub_claim_prefix`에 `:ref:refs/heads/dev`를 붙여 입력한다. `AmiId`에는 서울 리전 SSM 공개 파라미터 `/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64`의 현재 AMI ID를 입력하고, 후속 스택 업데이트에는 기존 값을 유지한다. 계정에 `token.actions.githubusercontent.com` OIDC 공급자가 이미 있다면 `ExistingGitHubProviderArn`에 해당 ARN을 입력한다.
2. 스택 출력으로 아래 GitHub Actions 저장소 변수를 설정한다. AWS 인증은 배포 시 발급되는 임시 자격 증명을 사용한다.

   | 스택 출력 | GitHub Actions 변수 |
   | --- | --- |
   | `DeployRoleArn` | `PODO_AWS_DEPLOY_ROLE_ARN` |
   | `ArtifactBucket` | `PODO_AWS_ARTIFACT_BUCKET` |
   | `InstanceId` | `PODO_AWS_INSTANCE_ID` |
   | `DeployDocumentName` | `PODO_AWS_DEPLOY_DOCUMENT` |
   | `BaseUrl` | `PODO_AWS_BASE_URL` |

3. `dev`에 변경이 들어가면 테스트와 JAR 빌드 후 자동 배포한다. 기존 커밋 재배포는 Actions에서 `dev`를 선택해 수동 실행한다. 스택의 `BaseUrl`에 `/swagger-ui.html`을 붙이면 Swagger UI가 열린다.

## 운영 메모

- Spring 시작에 실패하면 배포 명령은 직전 JAR을 복구한다. Flyway가 적용한 DB 변경은 별도로 관리한다.
- PostgreSQL 데이터는 EC2의 암호화된 EBS 볼륨에 저장된다. 인스턴스를 교체하면 새 인스턴스에는 기존 볼륨이 자동 연결되지 않는다. 기존 볼륨과 배포 버킷은 스택 삭제 후에도 보존되며 별도 요금이 발생할 수 있다.
- 카카오 로그인 설정은 Systems Manager로 접속해 서버의 `/etc/podo/app.env`에 저장하고 `sudo systemctl restart podo`로 적용한다. 실제 카카오 앱의 콜백 주소와 프론트 출처도 함께 설정해야 한다.
