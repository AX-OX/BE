# Spring Boot 로컬 빌드·Docker 실행

`demo/`는 Java 21, Spring Boot 4.1.1, Gradle Wrapper 9.8.1을 사용한다.
Spring Data JPA와 PostgreSQL JDBC 드라이버를 포함하며, 실행 시 DB 연결이 필요하다.
명령은 별도 안내가 없으면 저장소 루트 `BE/`에서 실행한다.

## 환경변수

`.env`가 없을 때만 예제를 복사하고 실제 접속 정보를 채운다.

```sh
cp .env.example .env
```

| 변수 | 값 / 용도 |
| --- | --- |
| `SPRING_DATASOURCE_URL` | 필수. 실제 TCP 접속 주소·DB·스키마를 포함한 PostgreSQL JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | 필수. `axox_app` |
| `SPRING_DATASOURCE_PASSWORD` | 필수. 로컬 `.env`에 입력하거나 배포 환경에서 Secret으로 주입 |
| `BACKEND_HOST_PORT` | 로컬 공개 포트, 기본 `8080` |
| `PORT` | Spring 수신 포트, 기본 `8080` |
| `SPRING_PROFILES_ACTIVE` | Cloud SQL은 `cloudsql`, 직접 TCP/Proxy는 `default` |
| `JWT_SECRET` | 필수. UTF-8 32바이트 이상 JWT 키 |
| `JWT_TTL` | 기본 `12h` |
| `INTERNAL_TOKEN` | 필수. 내부 API 토큰 |
| `CORS_ORIGIN` | 프론트 Origin, 기본 `http://localhost` |
| `JAVA_TOOL_OPTIONS` | 선택. JVM 실행 옵션 |

예제는 Cloud SQL Connector용 `jdbc:postgresql:///ax`를 사용한다.
`INSTANCE_CONNECTION_NAME`과 확인한 `CLOUD_SQL_IP_TYPES=PUBLIC` 또는 `PRIVATE`를
설정한다. 로컬 Connector 사용에는 ADC 인증도 필요하다. 직접 TCP/Proxy를 사용하려면
프로필을 `default`로 바꾸고 일반 TCP JDBC URL을 설정한다.
비밀번호를 URL에 넣지 않는다. Cloud SQL 연결 방식과 TLS 설정은
[GCP 가이드](../gcp/README.md)를 따른다. `.env`는 Git과 이미지 빌드 컨텍스트에서
제외된다. Spring Boot 자체는 `.env`를 자동 로딩하지 않으므로 호스트에서 직접
실행할 때는 IDE나 셸의 환경변수로 별도 주입한다.

Compose의 `--env-file .env`는 포트·프로필 치환에, 서비스의 `env_file`은
런타임 환경변수 전달에 사용한다. 셸에 같은 이름의 변수가 있으면 Compose 치환 시
우선한다. 포트는 `PORT`로 통일한다.

## 빌드·테스트

JDK 21을 선택한 뒤 실행한다.

```sh
cd demo
./gradlew clean test bootJar --no-daemon
```

macOS에서는 필요하면 `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`을
먼저 실행한다. CI도 같은 Gradle 작업을 실행한다.

기본 `test`는 integration 태그를 제외하고 계산·보안 슬라이스·Cloud SQL 설정 바인딩을
검증한다. DB·Docker·Cloud SQL 비밀번호 없이 실행한다. 연결 검사기 자체는 테스트 전용
H2에서 실제 `SELECT 1`을 실행해 확인한다. H2는 실행 JAR에 포함되지 않는다.

`./gradlew integrationTest`는 Docker가 필요하며 Testcontainers의 PostgreSQL+pgvector와
`db/init`의 스키마·시드로 최신 애플리케이션 전체를 검증한다. 실제 서버의 `/health`,
JWT API의 401, 내부 토큰 API의 403, JPA 쿼리 및 시작 쿼리도 이 테스트에서 확인한다.
이 결과는 운영 Cloud SQL 접속 성공을 의미하지 않는다.

## Docker 실행

Docker Engine이 실행된 상태에서 접속 가능한 DB 주소를 `.env`에 설정한다.
Compose는 백엔드 컨테이너 하나만 실행하며 DB나 Proxy를 생성하지 않는다.
컨테이너의 `127.0.0.1`은 호스트가 아니다. Docker Desktop 호스트의 로컬 Proxy에
접속하려면 `host.docker.internal`과 Proxy의 리슨 주소·접근 범위를 확인한다.

```sh
docker compose --env-file .env -f infra/local/compose.yaml config --quiet
docker compose --env-file .env -f infra/local/compose.yaml build backend
docker compose --env-file .env -f infra/local/compose.yaml up -d --wait --wait-timeout 60 backend
docker compose --env-file .env -f infra/local/compose.yaml ps
curl --fail --silent --show-error -i http://localhost:8080/health
docker compose --env-file .env -f infra/local/compose.yaml logs -f backend
docker compose --env-file .env -f infra/local/compose.yaml down
```

구성 확인에는 `config --quiet`를 사용해 비밀번호가 포함된 전체 설정 출력을 피한다.

Dockerfile은 JDK 21에서 테스트·빌드 후 실행 JAR만 JRE 21 이미지에 복사한다.
`RUN_TESTS=true`가 기본이며, `false`이면 테스트를 제외하고 패키징한다.
실행 사용자는 UID/GID `10001:10001`이다. `/health`에 대한 이미지
HEALTHCHECK를 Compose가 상속한다. `unhealthy` 상태 자체는 재시작을 유발하지 않는다.

## DB 및 인증 정책

`ddl-auto=validate`, `generate-ddl=false`, `spring.sql.init.mode=never`로
자동 테이블 생성·수정과 초기 SQL 실행을 끈다. develop의 Entity/Repository를 사용하며,
기존 DB 스키마와 매핑이 다르면 검증 단계에서 기동이 실패한다.

애플리케이션 시작 시 별도로 `SELECT 1`을 실행하고 실패하면 시작을 실패시킨다.

`GET /health`는 인증 없이 `{"status":"UP"}`를 반환한다. DB 쿼리를 수행하지
않으므로 DB 정상 여부를 증명하지 않는다. JPA 초기화에는 DB 연결이 필요하므로
실행 DB 설정이 없거나 연결할 수 없으면 애플리케이션이 시작되지 않을 수 있다.
develop의 JWT Bearer·stateless 인증과 내부 토큰 정책을 유지한다. 인증·Swagger·업무 API의
상세 정책은 루트 README를 따른다.
