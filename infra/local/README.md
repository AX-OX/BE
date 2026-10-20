# AX Navigator AI — DB 없는 Spring Boot Docker 환경

`demo/` 백엔드를 PostgreSQL·JPA 없이 실행한다. Java 21, Spring Boot 4.1.1,
Gradle Wrapper 9.8.1과 기존 `application.properties`를 사용한다.
아래 Docker 명령은 모두 **저장소 루트 `BE/`**에서 실행한다.

## 의존성과 설정 정리

`demo/build.gradle`에서 다음 세 의존성을 제거했다.

- `org.springframework.boot:spring-boot-starter-data-jpa`
- `org.postgresql:postgresql`
- `org.springframework.boot:spring-boot-starter-data-jpa-test`

기존 소스에는 Entity, Repository, DataSource 설정이나 SQL 파일이 없었다.
비즈니스 코드와 데이터 모델 문서를 삭제하지 않았다. H2 등 다른 DB도 추가하지
않았다. JPA·Hibernate·JDBC가 실행 클래스패스에서 빠지므로 DB 자동 설정을
강제로 비활성화하는 `spring.autoconfigure.exclude` 등은 필요하지 않다.

`.env.example`과 로컬 `.env`에서 DB 환경변수 및 DB 필수 안내를 제거했다.
기존 로컬 포트·프로필 값은 유지했다. `.env`는 Git 및 Docker 빌드 컨텍스트에서
제외한다. 애플리케이션 이름과 다음 설정은 유지한다.

```properties
spring.application.name=demo
server.port=${PORT:8080}
server.address=0.0.0.0
```

## Health API와 접근 정책

인증 정보 없이 다음 API를 호출할 수 있다. DB나 외부 서비스를 조회하지 않는다.

```http
GET /health
HTTP/1.1 200
Content-Type: application/json

{"status":"UP"}
```

`HealthController`는 상태 JSON만 반환한다. 기존 Spring Security 의존성은
유지하고 `SecurityConfiguration`에서 `GET /health`만 `permitAll()`로 허용했다.
다른 요청의 인증 요구 및 기존 기본 HTTP Basic/form login 동작과 CSRF 설정은
유지한다. 사용자 정의 로그인·JWT 기능은 추가하지 않았다.
설정 방식은 [Spring Security 공식 문서](https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html)를 따른다.

## Docker 구성

- 빌드 단계: `eclipse-temurin:21-jdk-jammy`에서 저장소 Gradle Wrapper 사용
- 실행 단계: `eclipse-temurin:21-jre-jammy`에 실행 JAR 하나만 복사
- 실행 사용자: UID/GID `10001:10001`; Java가 PID 1로 실행되어 종료 신호 수신
- 최종 이미지에는 Gradle, 소스, 테스트 결과, JDK와 `.env`를 포함하지 않음
- 기본 빌드는 `RUN_TESTS=true`로 `clean build`를 실행하여 테스트까지 검증
- `RUN_TESTS=false`를 명시하면 `clean bootJar -x test`로 패키징만 수행
- `EXPOSE 8080`은 메타데이터이며 실제 수신 포트는 `PORT` 환경변수로 결정

Dockerfile의 `HEALTHCHECK`는 실행 이미지에 포함된 curl로 컨테이너 내부
`http://127.0.0.1:${PORT:-8080}/health`를 호출한다. 간격 10초, 요청 제한 2초,
검사 제한 3초, 시작 유예 20초, 연속 실패 허용 3회로 구성했다.
HTTP 오류나 연결 실패 시 검사가 실패한다. Compose는 이 이미지 검사를 상속한다.
Docker의 `unhealthy` 상태 자체는 컨테이너를 재시작시키지 않는다.

Compose 서비스는 `backend` 하나이며 PostgreSQL·AI Agent 컨테이너는 없다.
루트 Dockerfile로 빌드하고 `unless-stopped` 재시작 정책과 `application` bridge
네트워크를 사용한다. 기본 매핑은 `127.0.0.1:8080:8080`이다.
향후 동일 네트워크에 추가된 서비스는 `backend:<PORT>`로 접근할 수 있다.

## 환경변수

이미 `.env`가 준비되어 있다면 재사용한다. 새 환경에서 파일이 없을 때만 복사한다.

```sh
cp .env.example .env
```

| 변수 | 기본값 / 용도 |
| --- | --- |
| `BACKEND_HOST_PORT` | 8080, 로컬 호스트 공개 포트 |
| `PORT` | 8080, Spring 수신 포트 및 Compose 컨테이너 target 포트 |
| `SPRING_PROFILES_ACTIVE` | `default`; 현재 별도 프로필 설정 파일 없음 |
| `JAVA_TOOL_OPTIONS` | 선택 사항, JVM 메모리 등 런타임 옵션 |

필수 DB 환경변수나 API Key는 없다. `--env-file .env`는 Compose 포트·프로필
치환에, 서비스의 `env_file`은 런타임 환경변수 전달에 사용한다.
셸에 같은 이름의 변수가 있으면 Compose 치환 시 `.env`보다 우선한다.
`SERVER_PORT`를 별도로 지정하면 파일 설정을 덮어써 포트 매핑·Healthcheck와
불일치할 수 있으므로 포트는 `PORT`로 통일한다.

## 빌드·실행·중지

Docker Desktop 또는 Docker Engine을 실행한 상태에서 진행한다.

```sh
# 구성 검증
docker compose --env-file .env -f infra/local/compose.yaml config --quiet

# 테스트를 포함한 이미지 빌드
docker compose --env-file .env -f infra/local/compose.yaml build backend

# 실행하고 Healthcheck 통과까지 대기
docker compose --env-file .env -f infra/local/compose.yaml up -d --wait --wait-timeout 60 backend

# 상태 및 API 확인
docker compose --env-file .env -f infra/local/compose.yaml ps
curl --fail --silent --show-error -i http://localhost:8080/health

# 로그 확인
docker compose --env-file .env -f infra/local/compose.yaml logs -f backend

# 중지 및 컨테이너·네트워크 제거
docker compose --env-file .env -f infra/local/compose.yaml down
```

Dockerfile만 사용한 빌드·실행도 가능하다. 아래 매핑은 `.env`의 `PORT=8080` 기준이다.

```sh
docker build -t ax-navigator-backend:local .
docker run --rm --env-file .env -p 127.0.0.1:8080:8080 ax-navigator-backend:local

# 필요한 경우에만 테스트를 제외한 이미지 빌드
docker build --build-arg RUN_TESTS=false -t ax-navigator-backend:local .
```

포트를 바꾸려면 `.env`의 `PORT`와 `BACKEND_HOST_PORT`를 수정한 뒤
`docker compose ... up -d --wait`를 다시 실행한다. 이미지 재빌드는 필요 없다.
Cloud Run에서도 런타임에 주입된 `PORT`로 수신한다.

호스트 Gradle 빌드에는 JDK 21이 필요하다. macOS의 기본 Java가 다르면
`export JAVA_HOME=$(/usr/libexec/java_home -v 21)`로 선택한다.

```sh
cd demo
./gradlew --no-daemon clean build
# 테스트만 실행
./gradlew --no-daemon test
```

`DemoApplicationTests`는 실제 임의 포트의 서버를 띄워 컨텍스트 시작,
무인증 `/health`의 HTTP 200·JSON 응답, 다른 경로의 인증 요구를 검증한다.
테스트용 DB, DB 접속 환경변수와 테스트 전용 자동 설정 제외가 필요하지 않다.

## 검증 결과 (2026-10-19)

| 항목 | 실제 결과 |
| --- | --- |
| Java 21 / Gradle 9.8.1 `clean build` | 성공, 테스트 포함 |
| Gradle 테스트 | 3개 성공, 실패·오류 0개 |
| Docker 이미지 빌드 | 성공, Linux arm64, 이미지 빌드 중에도 테스트 실행 |
| Compose 설정 / 실행 | 성공, backend 하나만 실행 |
| Spring 시작 | `Started DemoApplication`, 포트 8080 |
| 컨테이너 상태 | `running`, `healthy`, 재시작 횟수 0 |
| 비-root 실행 | 컨테이너 `id`에서 UID/GID `10001:10001` 확인 |
| `http://localhost:8080/health` | 인증 없이 HTTP 200, `{"status":"UP"}` |
| DB 의존성 제거 | 실행 JAR에 JPA·Hibernate·JDBC·PostgreSQL·내장 DB 라이브러리 없음 |
| 외부 DB 없이 실행 / 포트 변경 | `--network none`, DB 환경변수 없이 `PORT=9090`의 `/health` 및 Docker Healthcheck 정상; 임시 검증 컨테이너 제거 |

검증에 사용한 Docker Engine은 29.7.2이며 Compose는 v5.4.0이다.
기본 Compose 백엔드는 정상 실행 상태로 유지했다. 실제 `.env`는 로컬에 유지하고
Git에서 제외했다. 이전 DB 미설정 시작 실패는 의존성 제거로 해결했다.

## 추후 DB 연결 시 필요한 설정

1. `spring-boot-starter-data-jpa`, PostgreSQL JDBC 런타임 드라이버,
   `spring-boot-starter-data-jpa-test` 의존성을 필요한 범위에 다시 추가한다.
2. 환경별 `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`,
   `SPRING_DATASOURCE_PASSWORD`를 Secret 또는 로컬 `.env`로 주입한다.
3. Entity/Repository, 스키마·마이그레이션 및 JPA 설정을 실제 데이터 모델에 맞게
   구현하고 테스트 DB 환경을 별도로 준비한다. 자동 DDL 정책도 이때 결정한다.
4. `/health`는 현재 프로세스 상태 확인 용도다. DB 준비 상태를 반영해야 할 경우
   별도 readiness 정책을 정한다.

PostgreSQL/pgvector 설치, AI Agent, Kubernetes YAML, GCP 리소스와 GitHub Actions는
이번 단계에서 구성하지 않았다.
