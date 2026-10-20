# AX Navigator AI — Backend

Spring Boot 4.1.1 / Java 21. 코드는 `demo/`(패키지 `com.axnavigator`).
팀 공통 기획 문서는 별도로 공유하며 저장소에 포함하지 않는다. 테이블·API·필드명은 팀 합의 계약을 따른다.

## 폴더

| 경로 | 내용 |
|---|---|
| `demo/` | Spring Boot 애플리케이션 |
| `db/init/` | `00_schema_postgres16.sql`, `01_seed_input_postgres.sql`, `03_pgvector.sql` (postgres `/docker-entrypoint-initdb.d`) |
| `db/fixtures/` | 시연용 상태 SQL (자동 실행 안 함) |
| `db/docs/` | 근거 원문 `{documentId}.md` (`GET /api/docs/{documentId}`가 읽음) |
| `demo/src/main/resources/ax/config/` | AX점수·ROI·성숙도 정책 JSON |
| `demo/src/test/resources/ax/` | 계산 정답(`eval/`)과 시드 CSV(`seed/`) — DB 없는 계산 테스트용 |

## 로컬 실행

```bash
# 1) PostgreSQL 16 + pgvector  (로컬에 이미 PostgreSQL이 5432를 쓰고 있으면 -p 5433:5432로 바꾸고 URL도 5433)
docker run -d --name ax-pg -e POSTGRES_USER=ax -e POSTGRES_PASSWORD=ax -e POSTGRES_DB=ax \
  -p 5432:5432 pgvector/pgvector:0.8.1-pg16

# 2) 스키마·시드 (순서대로)
for f in db/init/00_schema_postgres16.sql db/init/01_seed_input_postgres.sql db/init/03_pgvector.sql; do
  docker exec -i ax-pg psql -v ON_ERROR_STOP=1 -U ax -d ax < "$f"
done

# 3) (선택) 시연 상태: 승인 대기 → 승인 후
# docker exec -i ax-pg psql -v ON_ERROR_STOP=1 -U ax -d ax < db/fixtures/02_demo_waiting_approval.sql
# docker exec -i ax-pg psql -v ON_ERROR_STOP=1 -U ax -d ax < db/fixtures/03_demo_after_requester_approval.sql

# 4) 실행 (demo/ 에서)
cd demo
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/ax \
SPRING_DATASOURCE_USERNAME=ax SPRING_DATASOURCE_PASSWORD=ax \
JWT_SECRET=local-dev-jwt-secret-at-least-32-bytes!! \
INTERNAL_TOKEN=local-internal-token \
./gradlew bootRun
```

스키마는 SQL이 주인이다. JPA는 `ddl-auto=validate`만 하므로 SQL과 엔티티가 다르면 기동이 실패한다.

### 환경 변수

| 변수 | 필수 | 기본값 | 설명 |
|---|---|---|---|
| `SPRING_DATASOURCE_URL` | | `jdbc:postgresql://localhost:5432/ax` | 업무 DB `ax` |
| `SPRING_DATASOURCE_USERNAME` | | `ax` | |
| `SPRING_DATASOURCE_PASSWORD` | ✓ | | |
| `JWT_SECRET` | ✓ | | HS256 키, 32바이트 이상 (짧으면 기동 실패) |
| `INTERNAL_TOKEN` | ✓ | | `/internal/**`의 `X-Internal-Token` 값 |
| `AGENT_BASE_URL` | | (빈 값) | 예: `http://agent:8000` |
| `AGENT_ENABLED` | | `false` | `false`면 Agent를 호출하지 않고 로그만 남김 |
| `CORS_ORIGIN` | | `http://localhost` | 쉼표로 여러 개 가능 |
| `AX_DOCS_DIR` | | `../db/docs` | 근거 원문 폴더 |
| `AX_EVALUATION_AS_OF` | | `2026-10-01` | 계산 기준일 (최근 3개 완료 월 = 2026-07~09) |
| `AX_TIME_ZONE` | | `Asia/Seoul` | `decidedAt` 등 시각 표기 기준 |
| `PORT` | | `8080` | |

## 테스트

```bash
cd demo
./gradlew clean build        # 단위·슬라이스 테스트. DB·Docker 불필요 (Dockerfile이 이 명령을 실행)
./gradlew integrationTest    # @Tag("integration"): Testcontainers pgvector/pgvector:0.8.1-pg16 + db/init (Docker 필요)
```

## API

모든 오류는 `{ "code", "message", "hint" }` 형식이다 (기준 문서 §7). 없는 ID는 `404 NOT_FOUND` + `hint.validIds`.

### 내부 API (`/internal/**`, `X-Internal-Token` 필수 — 없거나 틀리면 403)

| Method | Path | 요청 | 응답 | 오류 |
|---|---|---|---|---|
| GET | `/internal/companies/{companyId}` | | `{ companyId, companyName, industry, employeeCount, axBudgetKrw, departments: [{ departmentId, departmentName, managerName, managerEmail, managerPhone }] }` | 404 |
| GET | `/internal/companies/{companyId}/processes?departmentId=` | | `[{ processId, departmentId, departmentName, processName, purpose, processSteps, standardizationLevel, requiresApproval, exceptionRules }]` | 404 (다른 기업 부서 포함) |
| GET | `/internal/companies/{companyId}/metrics?departmentId=` | | `[{ processId, processName, departmentId, months: [{ metricId, periodStart, periodEnd, processingCount, avgProcessingMinutes, errorRate, hourlyLaborCostKrw, dataSource }], monthlyAvgCount, annualHours, weightedHourlyLaborCostKrw, dataConfidence, missingMonths }]` | 404 |
| GET | `/internal/companies/{companyId}/systems` | | `[{ systemId, systemName, systemType, apiAvailable, dataAccessLevel, cloudBased, processes: [{ processId, usagePurpose }] }]` | 404 |
| GET | `/internal/companies/{companyId}/budget` | | `{ companyId, axBudgetKrw }` | 404 |
| GET | `/internal/solutions` | | 카탈로그 전체 (`targetProcessId`, `dependsOnSolutionId`, 비율 0~1) | |
| POST | `/internal/ax/roi` | `{ processId, solutionId }` | 기준 문서 §7 형식 (AX점수 + low/base/high 시나리오) | 404 `NOT_FOUND`, 400 `VALIDATION_ERROR`(`hint.expectedProcessId`), 400 `EXCLUDED_PROCESS`(`hint.reason`), 400 `INSUFFICIENT_DATA`(`hint.missingMonths`) |
| POST | `/internal/ax/portfolio` | `{ companyId, candidates?: [{ processId, solutionId }], maxItems?: 3 }` | 기준 문서 §7 형식 + `warnings` | 404, 400 |

- 내부 API는 담당자 이메일·연락처를 **마스킹하지 않는다** (마스킹은 Agent Guardrail 담당).
- 지표·계산의 기준일은 `AX_EVALUATION_AS_OF`(기본 2026-10-01). 최근 3개 완료 월 중 빠진 월이 있으면 그 업무 후보는 선정 가능 후보에서 빠진다.

### 로컬 확인 (curl)

```bash
H='X-Internal-Token: local-internal-token'
curl -s localhost:8080/internal/companies/1 -H "$H"
curl -s "localhost:8080/internal/companies/1/metrics?departmentId=103" -H "$H"
curl -s "localhost:8080/internal/companies/1/processes?departmentId=104" -H "$H"
# → 404 {"code":"NOT_FOUND","message":"부서 104는 없다","hint":{"validIds":[101,102,103]}}
curl -s localhost:8080/internal/ax/roi -H "$H" -H 'Content-Type: application/json' -d '{"processId":207,"solutionId":507}'
curl -s localhost:8080/internal/ax/portfolio -H "$H" -H 'Content-Type: application/json' -d '{"companyId":1}'
# → selected [507, 508, 509], totalAxScore 265.0, totalFirstYearTcoKrw 295000000, remainingBudgetKrw 5000000,
#   evaluatedCombinations 56, validCombinations 39, runnerUp [504, 507, 509] 261.3,
#   greedyTop3 [507, 508, 504] → BUDGET_EXCEEDED, MISSING_DEPENDENCY
```

## API 응답 보완 규칙

- 포트폴리오 최상위 `warnings`는 `[{processId, solutionId, code: "INSUFFICIENT_METRICS", missingMonths: ["2026-08"]}]` 형식이며 경고가 없으면 `[]`이다. 지표 부족 후보는 선정에서 제외하고 다른 후보 계산은 계속한다. `excluded`는 정책상 제외 업무이고, 후보별 `warnings`는 `LOW_STANDARDIZATION` 등의 문자열 배열이다.
- 지표 API는 최근 3개 완료 월 중 한 달이라도 누락되면 `monthlyAvgCount`, `annualHours`, `weightedHourlyLaborCostKrw`를 `null`로 반환한다. 존재하는 월은 `months`, 누락 월은 `missingMonths`에 유지한다. 정상 집계의 응답 정밀도는 각각 소수 2자리, 소수 2자리, 원 단위이며 HALF_UP을 쓴다. 운영 계산은 반올림 전 값을 사용한다.
- `greedyTop3.violations`는 `BUDGET_EXCEEDED`, `MISSING_DEPENDENCY`, `DUPLICATE_PROCESS`를 사용한다. 복수 위반은 모두 반환하고 없으면 `[]`이다.
- 로그인 실패는 `401 UNAUTHORIZED`와 일반 메시지 `이메일 또는 비밀번호가 올바르지 않습니다.`로 통일한다. 예상하지 못한 서버 오류는 `500 INTERNAL_ERROR`이며 상세 예외는 서버 로그에만 기록한다.
- 승인 시각 `decidedAt`은 Spring이 승인 처리 시 서버 시각으로 생성하고 ISO 8601 오프셋 포함 형식으로 표기한다. 표기 시간대 `AX_TIME_ZONE`의 기본값은 `Asia/Seoul`이다. 이 설정은 계산 기준일 `AX_EVALUATION_AS_OF`와 독립적이다. 승인 API는 추후 구현 대상이다.

## Swagger로 API 테스트

서버 실행 후 http://localhost:8080/swagger-ui/index.html 에 접속한다. OpenAPI JSON은 `/v3/api-docs`에서 확인할 수 있다.

1. 우측 상단 **Authorize**에서 `InternalToken`에 실행 시 설정한 `INTERNAL_TOKEN` 값을 입력한다. 로컬 예시는 `local-internal-token`이며 `Bearer` 접두어는 넣지 않는다.
2. API를 펼쳐 **Try it out**을 누르고 값을 입력한 뒤 **Execute**한다.
3. 기업 조회는 `companyId=1`, 부서 필터는 `departmentId=103`으로 확인한다. 필터 없이 조회하려면 departmentId를 비운다.
4. AX/ROI 요청 예시는 `{"processId":207,"solutionId":507}`, 포트폴리오는 `{"companyId":1,"maxItems":3}`이다.

현재 제공되는 9개 API는 조회·계산·상태 확인 용도이며 데이터를 변경하지 않는다. `/health`와 Swagger 문서에는 인증이 필요 없고, `/internal/**`는 기존 내부 토큰 인증을 유지한다. 회원가입·로그인 API는 아래 인증 안내를 참고한다. 승인 API는 아직 구현되지 않았다.

## 회원가입·로그인 및 JWT

- `POST /api/auth/signup`: `{"email":"newuser@gaon.example","password":"ChangeMe123!","name":"홍길동"}` → 201 `{"userId":603}`. 기업은 서버의 기본 기업(1)으로 지정하며 자동 로그인하지 않는다.
- `POST /api/auth/login`: `{"email":"analyst1@gaon.example","password":"ChangeMe123!"}` → 200 `{accessToken, user: {userId, name, companyId}}`.
- 시드 계정: `analyst1@gaon.example`(601), `analyst2@gaon.example`(602), 비밀번호는 모두 `ChangeMe123!`이다.
- 가입 비밀번호는 8자 이상 영문·숫자를 포함해야 하며 BCrypt로 저장한다. BCrypt의 UTF-8 72바이트 한도를 넘으면 400이다. 이메일과 이름은 DB 길이 한도인 100자, 50자를 따른다.
- 이메일 중복은 409 `DUPLICATE_EMAIL`, 입력 오류는 400 `VALIDATION_ERROR`, 로그인 불일치는 401 `UNAUTHORIZED`다. 로그인 불일치 메시지는 이메일 존재 여부와 무관하게 동일하다.
- 로그인 API를 제외한 보호 API 요청은 `Authorization: Bearer {accessToken}`을 사용한다. 회원가입도 인증 없이 호출한다. SSE도 fetch 스트리밍에 같은 헤더를 넣으며 `?token=`과 `?access_token=`은 인증에 사용하지 않는다.
- 401이면 프론트에서 토큰을 삭제하고 로그인 화면으로 이동한다. 로그아웃도 프론트의 토큰 삭제만 수행하며 서버 로그아웃 API·블랙리스트·refresh token은 제공하지 않는다. 로그아웃으로 발급 토큰 자체가 만료되지는 않는다.
- JWT의 `sub`는 사용자 ID 문자열이고 `companyId`, `name`, `iat`, `exp`를 포함한다. 사용자 정보나 기업 ID를 요청 body로 받아 신뢰하지 않는다.
- Swagger의 BearerAuth에는 로그인 응답의 accessToken만 입력한다. 내부 API는 별도의 InternalToken을 사용한다.

환경 변수는 커밋하지 않는 `.env`에서 관리한다. `JWT_SECRET`은 UTF-8 32바이트 이상이며 기본값이 없다. `JWT_TTL`은 양수 Duration이고 기본 `12h`(예: `1h`)다. `CORS_ORIGIN`은 프론트 주소이며 기본 `http://localhost`다. Spring은 `.env`를 자동 로드하지 않으므로 IDE 실행 환경이나 셸에서 주입해야 한다. 저장소 루트에서 셸 형식으로 작성한 `.env`를 사용한다면 다음처럼 실행할 수 있다.

```sh
set -a
source .env
set +a
cd demo
./gradlew bootRun
```

인증·보안 테스트를 포함한 DB 없는 검증은 `./gradlew clean build`, PostgreSQL 시드 로그인·가입 통합 테스트는 `./gradlew integrationTest`로 실행한다(Docker 필요). 작업 API가 아직 없으므로 유효한 JWT로 `/api/ai/tasks`를 호출하면 보안을 통과한 뒤 404, 토큰이 없거나 만료됐으면 401이다.
