# Cloud SQL 실제 연결·배포

## 구현된 동작

- `application.properties`를 `application.yml`로 전환했다.
- JPA, PostgreSQL JDBC 드라이버, Java Cloud SQL Connector 1.30.1을 포함한다.
- `SPRING_PROFILES_ACTIVE=cloudsql`이면 `application-cloudsql.yml`의 SocketFactory로 연결한다.
- URL·사용자·비밀번호·인스턴스·IP 방식은 환경변수로 받는다. ADC로 실행 계정을 사용한다.
- `ddl-auto: validate`, `generate-ddl: false`, SQL 초기화 `never`로 기존 테이블을 변경하지 않는다.
- 스키마는 DB 사용자의 기존 `search_path`를 사용한다. develop의 Entity/Repository와
  업무 기능을 유지하며 기존 SQL 스키마를 검증한다. JWT·Agent·CORS·Swagger 설정도
  `application.yml`에 통합했다.
- 시작 시 `DatabaseConnectionVerifier`가 실제 DataSource에서 읽기 전용 `SELECT 1`을
  실행한다. 실패하면 시작이 실패한다. 성공하면 아래 로그를 남긴다.

```text
Database connectivity verified: SELECT 1 returned 1 (database=PostgreSQL)
```

`GET /health`와 develop의 JWT Bearer·내부 토큰 인증 정책은 유지했다.
`/health`는 계속 프로세스 상태만 반환하며 DB 확인 기준으로 사용하지 않는다.
쿼리는 시작할 때 한 번 실행하므로 이후 DB 가용성의 지속적인 감시를 의미하지 않는다.

## 확인된 대상과 접근 한계

| 항목 | 값 |
| --- | --- |
| 백엔드 프로젝트 | `my-project-axox` |
| DB 프로젝트 | `axox-db-trial` |
| 인스턴스 | `axox-db-trial:asia-northeast1:free-trial-first-project` |
| DB / 사용자 | `ax` / `axox_app` |
| CRUD 역할 | `axox_app_rw` |
| Secret | `axox-db-password` |
| DB 버전 | 사용자 제공 PostgreSQL 18.6 |

저장소에는 Dockerfile·로컬 Compose·빌드 CI만 있고 GCP 배포 정의는 없다.
현재 실행 환경에서 `gcloud`, 기본 GCP 인증 설정 및 ADC 파일을 찾지 못했고,
연결 가능한 도구 검색에서도 Cloud Run/Cloud SQL 관리 도구를 찾지 못했다.
따라서 실제 배포 대상·실행 계정·Secret 프로젝트·버전은 조회하지 못했으며,
IAM 변경·배포·Cloud SQL 쿼리 실행도 완료하지 않았다.

## 1. 콘솔에서 기존 리소스 확인

1. 프로젝트 **my-project-axox** → **Cloud Run → 서비스**에서 기존 백엔드의 이름과
   리전을 기록한다. 없다면 Compute Engine VM 및 GKE 워크로드를 확인한다.
   **Cloud Run이 아니면 아래 Cloud Run 배포 명령은 사용하지 않는다.**
2. 해당 Cloud Run 서비스 → **보안** 또는 **새 버전 수정 및 배포 → 보안 → 서비스 계정**에서
   실제 실행 계정 이메일을 기록한다. 확인만 할 때 배포 버튼을 누르지 않는다.
3. 프로젝트 **axox-db-trial** → **Cloud SQL → free-trial-first-project → 연결 → 네트워킹**에서
   Public/Private IP 상태를 확인한다. Public IP가 활성화되어 있고 접근 가능한 경우
   `CLOUD_SQL_IP_TYPES=PUBLIC`을 선택할 수 있다. Private IP를 사용할 경우
   `PRIVATE`로 지정하고 Cloud Run에서 대상 DB에 도달하는 VPC 연결·라우팅을 먼저 설정한다.
   Connector가 네트워크 경로를 만들어 주지는 않는다. 인스턴스 IP 설정은 임의로 바꾸지 않는다.
4. **두 프로젝트의 Secret Manager**에서 각각 `axox-db-password`를 찾아 소유 프로젝트를
   확인한다. 해당 프로젝트의 **프로젝트 정보 → 프로젝트 번호**와 Secret의 **버전**에서
   사용할 활성 버전 번호를 기록한다. 비밀번호 값을 복사하거나 채팅에 보내지 않는다.
5. DB 스키마 확인은 Cloud SQL Studio에서 DB `ax`에 접속해 아래 쿼리로 수행한다.
   앱 검색 경로를 확인하려면 `axox_app` 세션을 사용한다.

```sql
SELECT current_database(), current_user, current_schema();
SHOW search_path;
SELECT schemaname, tablename FROM pg_catalog.pg_tables
WHERE schemaname NOT IN ('pg_catalog', 'information_schema')
ORDER BY schemaname, tablename;
SELECT pg_has_role(current_user, 'axox_app_rw', 'USAGE');
```

`SELECT 1` 자체는 테이블을 사용하지 않지만 JPA 시작 시 develop의 테이블 매핑을
검증한다. 실제 스키마와 CRUD·시퀀스 권한도 확인해야 한다.

## 2. 콘솔에서 프로젝트 간 IAM·Secret 권한 설정

1. **my-project-axox → API 및 서비스 → 라이브러리**에서 Cloud SQL Admin API를 활성화한다.
2. **axox-db-trial**에서도 Cloud SQL Admin API를 활성화한다.
3. **axox-db-trial → IAM 및 관리자 → IAM → 액세스 권한 부여**에서 새 주 구성원으로
   위에서 확인한 **백엔드 실행 서비스 계정 이메일**을 넣고 **Cloud SQL Client**
   (`roles/cloudsql.client`)를 부여한다. 백엔드 프로젝트에만 부여하면 안 된다.
4. **Secret 소유 프로젝트 → Secret Manager → axox-db-password → 권한**에서 같은 실행
   계정에 **Secret Manager Secret Accessor** (`roles/secretmanager.secretAccessor`)를
   해당 Secret 범위로 부여한다. Secret Manager API도 소유 프로젝트에서 활성화한다.
5. 실제 Secret 값과 DB 사용자 `axox_app`의 비밀번호가 일치하는지 담당자가 확인한다.
   Secret 수정만으로 DB 사용자 비밀번호가 자동 변경되지는 않는다.

DB의 `axox_app_rw`는 PostgreSQL 역할이다. Cloud SQL IAM 역할은 DB CRUD 권한을
대체하지 않는다. 비밀번호 로그인이므로 IAM DB 인증은 켜지 않는다.
이 Java Connector 구성에는 Cloud Run의 Unix 소켓 연결 추가나 서비스 계정 JSON 키가
필요하지 않다. [공식 연결 안내](https://cloud.google.com/sql/docs/postgres/connect-run),
[Secret 권한·참조 안내](https://cloud.google.com/run/docs/configuring/services/secrets)를 따른다.

## 3. 수정된 소스로 기존 Cloud Run 서비스 재배포

이전 이미지의 환경변수만 수정해서는 Connector와 시작 쿼리가 추가되지 않는다.
반드시 이번 수정 소스로 이미지를 다시 빌드해야 한다.

콘솔 상단 **Cloud Shell 활성화 → 터미널 메뉴 → 파일 업로드**로 제공된
`axox-backend-cloudsql.tar.gz`를 업로드한다. 또는 수정된 작업 디렉터리를 직접 사용한다.
원격 저장소의 이전 커밋을 clone한 것만으로는 이번 로컬 변경이 포함되지 않는다.

```sh
tar -xzf axox-backend-cloudsql.tar.gz
cd axox-backend
```

콘솔에서 확인한 값을 아래 변수에 입력한다. Secret 프로젝트·버전·서비스 이름을
임의로 정하지 않는다.

```sh
AXOX_SERVICE='<기존 Cloud Run 서비스 이름>'
AXOX_REGION='<기존 서비스 리전>'
AXOX_SECRET_PROJECT='<Secret이 저장된 프로젝트 ID>'
AXOX_SECRET_VERSION='<확인한 활성 버전 번호>'
AXOX_IP_TYPE='<PUBLIC 또는 PRIVATE>'

# 기존 서비스가 있는지 확인하고 실제 실행 계정을 읽는다.
AXOX_RUNTIME_SA="$(gcloud run services describe "$AXOX_SERVICE" \
  --project=my-project-axox --region="$AXOX_REGION" \
  --format='value(spec.template.spec.serviceAccountName)')"
AXOX_SECRET_NUMBER="$(gcloud projects describe "$AXOX_SECRET_PROJECT" \
  --format='value(projectNumber)')"
```

위 조회가 실패하거나 실행 계정이 비어 있으면 진행하지 않고 콘솔 값부터 확인한다.
**my-project-axox**의 API 라이브러리에서 Cloud Run·Cloud Build·Artifact Registry API를
활성화한다. 소스 배포 계정에는 Cloud Run Source Developer·Service Usage Consumer 및
실행 계정의 Service Account User 권한, 실제 빌드 계정에는 Cloud Run Builder 권한이
필요하다. 실행 계정과 빌드 계정을 구분하고 기존 빌드 설정의 계정을 사용한다.
[공식 소스 배포 권한 안내](https://cloud.google.com/run/docs/deploying-source-code)를 참고한다.

```sh
gcloud run deploy "$AXOX_SERVICE" \
  --project=my-project-axox --region="$AXOX_REGION" --source=. \
  --service-account="$AXOX_RUNTIME_SA" \
  --update-env-vars="SPRING_PROFILES_ACTIVE=cloudsql,SPRING_DATASOURCE_URL=jdbc:postgresql:///ax,SPRING_DATASOURCE_USERNAME=axox_app,INSTANCE_CONNECTION_NAME=axox-db-trial:asia-northeast1:free-trial-first-project,CLOUD_SQL_IP_TYPES=$AXOX_IP_TYPE,SPRING_JPA_HIBERNATE_DDL_AUTO=validate,SPRING_JPA_GENERATE_DDL=false,SPRING_SQL_INIT_MODE=never" \
  --update-secrets="SPRING_DATASOURCE_PASSWORD=projects/$AXOX_SECRET_NUMBER/secrets/axox-db-password:$AXOX_SECRET_VERSION"
```

소스 루트의 Dockerfile로 테스트를 포함해 빌드하고 기존 서비스를 갱신한다.
호출 IAM을 변경하는 `--allow-unauthenticated`는 추가하지 않는다.
기존 설정에 일반 환경변수 `SPRING_DATASOURCE_PASSWORD`가 남아 있으면 Secret 참조와
충돌하지 않도록 콘솔에서 해당 항목의 유형을 확인한다.

Cloud Run 콘솔의 **새 버전 수정 및 배포 → 변수 및 보안 비밀**에서 직접 설정한다면
위 `--update-env-vars`의 값을 일반 변수로 지정하고, 비밀번호는 **보안 비밀 참조**로
`projects/<Secret 프로젝트 번호>/secrets/axox-db-password`와 실제 버전을 지정한다.
UI로 배포할 때도 이번 소스로 빌드한 새 이미지를 선택해야 한다.

## 4. 배포된 백엔드에서 실제 SELECT 1 성공 확인

콘솔의 해당 서비스 **버전**에서 이번에 배포한 버전 이름을 확인하고 **로그**에서
그 버전의 아래 메시지를 찾는다. 이전 버전이나 H2 테스트 로그와 구분한다.

```text
Database connectivity verified: SELECT 1 returned 1 (database=PostgreSQL)
```

이 메시지는 배포된 애플리케이션의 실제 DataSource에서 `SELECT 1`이 성공한 뒤에만
기록된다. 오류 없이 새 버전이 Ready이며 트래픽도 새 버전으로 향하는지 확인한다.
로그 수집 지연 시 잠시 뒤 다시 확인한다. 이 메시지를 찾지 못하면 연결 검증을
완료 처리하지 않는다. `/health` 200은 연결 검증을 대체하지 않는다.

연결·권한 오류가 있으면 해당 버전의 Connector/JDBC 오류를 확인한다. 성공 로그는
연결·쿼리 실행을 증명하지만 모든 업무 CRUD가 정상임을 증명하지는 않는다.

## 로컬 검증 결과 (2026-10-20)

merge 후 검증은 `./demo/gradlew -p demo clean build`로 진행한다.
기본 테스트는 DB·Docker 없이 계산·인증·Cloud SQL 설정과 H2의 연결 검사기를 검증하고,
`integrationTest`는 Testcontainers PostgreSQL에서 전체 앱·스키마·쿼리·인증을 검증한다.
Cloud SQL 설정 테스트는 실제 네트워크 연결을 열지 않는다.
운영 GCP 설정·재배포·Cloud SQL 쿼리 검증과 구분한다.
