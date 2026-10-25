# 배포·장애 후 AI 작업 복구

## 변경 목적과 범위

BE에 작업이 저장된 직후 프로세스가 종료되면 기존 메모리 이벤트 기반 Agent 호출이
사라질 수 있었다. Agent가 종료되면 LangGraph 체크포인트는 남지만 실행을 다시
시작할 주체가 없었다. 복구 모드는 업무 DB에 실행 명령을 저장하고 AI Worker가
내부 HTTP API로 수령하게 한다. 새 Redis·Kubernetes·GCP 리소스는 필요하지 않다.

기본값은 **비활성화**다. 기존 FE API와 상태값은 유지한다. 기존 작업 디렉터리의
미커밋 변경이나 운영 환경 설정은 이 PR에 포함하지 않는다.

```text
FE → BE → 업무 DB: ai_tasks / 승인 기록 + ai_commands (같은 트랜잭션)
                     ↑                     ↓ claim / heartbeat
                     └── 결과 저장 ← AI Worker ↔ 기존 agent DB 체크포인트

PDF → BE → documents + ai_commands + ai_command_inputs (같은 트랜잭션)
                            ↓ Worker가 원본을 읽고 색인
                       결과 저장 → 명령 완료 → 임시 원본 삭제
```

## 구현과 보장

| 변경 | 동작과 이유 |
| --- | --- |
| `CommandQueue` | RUN·CONFIRM·RESUME·INGEST 명령을 업무 변경과 함께 저장한다. 접수·승인 직후 BE가 종료돼도 전달할 명령이 남는다. |
| 명령 수령 | 짧은 `FOR UPDATE SKIP LOCKED` 트랜잭션으로 한 Worker에 임대한다. LLM 처리 중에는 이 잠금을 유지하지 않는다. |
| 처리 권한 | 매 시도에 새 임대 토큰을 발급한다. DB 시각으로 90초 만료를 판단하며 Worker는 30초마다 갱신한다. |
| `CommandFenceFilter` | 내부 쓰기 요청에서 토큰 검증·명령 잠금·업무 변경을 같은 트랜잭션으로 묶는다. 만료·인계된 Worker는 409, 다른 작업 변경은 403이다. |
| 사용자 결정 보호 | 작업 행 잠금 후 더 최신 명령을 확인한다. 승인·확정 이후 이전 Worker의 늦은 이벤트가 상태를 되돌릴 수 없다. |
| 재시도 | 일시 오류는 2·4·8·16초 등 최대 60초 대기 후 재시도한다. 기본 총 5회이며 잘못된 입력은 재시도하지 않는다. |
| 결과 저장 | 성숙도 평가는 작업 잠금 아래 기존 평가 ID를 반환한다. 기존 로드맵 `taskId`·프로젝트 `itemId` 중복 반환을 재사용한다. 동일 문서 색인 콜백도 재수신 가능하다. |
| 완료 확인 | DB에 결과/사용자 대기가 저장됐거나 다음 사용자 명령이 저장된 뒤에만 명령을 완료한다. |
| 정리 | 완료·영구 실패 시 PDF 입력 행을 삭제한다. 1분 주기로 만료·재시도 소진을 처리하고 종료 명령은 기본 7일 뒤 삭제한다. |

작업 접수·사용자 확정·승인·거절은 각각 한 번의 논리 명령이다. 사용자 대기 상태의
작업은 자동 승인하지 않는다. 재시도 소진 시 실행 중 작업은 FAILED가 되지만 이미
저장된 사용자 대기·완료·거절 상태를 덮어쓰지 않는다.

## 내부 API 계약 v1

모든 요청에 `X-Internal-Token`이 필요하다. 복구 모드를 켜면 내부 토큰 필터를 활성화한다.
기존 공개 API의 인증 정책을 변경하는 PR은 아니다.

| 메서드·경로 (`/internal` 기준) | 요청 / 응답 |
| --- | --- |
| POST `/agent-commands/claim` | `{}` → 200 명령 / 204 대기 명령 없음 |
| POST `/agent-commands/{id}/heartbeat` | `{leaseToken}` → 204 |
| GET `/agent-commands/{id}/input` | `X-Command-Lease` → PDF bytes |
| POST `/agent-commands/{id}/complete` | `{leaseToken}` → 204 |
| POST `/agent-commands/{id}/fail` | `{leaseToken, retryable}` → 204 |

명령 응답은 `commandId, aggregateKey, taskId, documentId, kind, payload, leaseToken,
attempt, leaseSeconds, schemaVersion=1, superseded`다. `payload`는 기존 Agent API의
camelCase 요청 필드를 포함한다. `superseded=true`는 더 최신 사용자 결정이 저장됐다는
뜻이며, AI는 이전 상태 알림을 재전송하지 않고 해당 명령만 완료한다.

Worker의 작업/문서 콜백과 Business MCP의 업무 저장 요청에는 `X-Command-Id`와
`X-Command-Lease`를 전달한다. MCP는 이 두 헤더를 BE까지 전달한다. 계산·조회 API는
명령 토큰 없이 기존 내부 토큰으로 호출할 수 있다. 토큰과 payload는 로그에 출력하지 않는다.

## PDF 임시 보관 정책 변경

기존에는 원본을 보관하지 않았다. 복구 모드에서는 **업무 DB의 bytea 입력 테이블에
색인 완료까지 일시 보관**한다. 이미 사용하는 Cloud SQL의 접근 제어·저장을 활용해
별도 버킷·자격증명 없이 복구할 수 있도록 선택했다. 파일당 기존 20MB 제한을 유지한다.

- 성공·영구 실패: 입력 행 삭제.
- 미완료: 기본 업로드 후 24시간에 읽기를 차단하고, 다음 정리 주기(최대 약 1분)에 삭제·FAILED 처리.
- BE가 중지된 동안에는 정리가 실행되지 않으며 재시작 후 정리한다.
- 삭제는 운영 테이블의 논리적 삭제다. WAL·DB 백업에 남은 사본은 기존 DB 보관 정책을 따른다.
- PDF 업로드가 늘면 DB 저장량·백업량을 확인하고 객체 저장소로 분리할 수 있다.

## 배포·활성화 순서

이 문서는 PR을 검토·배포하기 위한 절차다. **운영 마이그레이션·기능 활성화는 수행하지 않았다.**

1. BE와 AI의 대응 PR을 모두 배포하되 플래그는 false로 유지한다. 각 저장소의 main 병합 시
   기존 Cloud Build 배포가 실행될 수 있으나, 이 기능은 환경변수를 바꾸기 전까지 켜지지 않는다.
2. 새 분석/업로드 접수를 잠시 중단하고 기존 RUNNING·QUEUED 작업과 UPLOADED 문서를 비운다.
   사용자 승인/확정 대기 체크포인트는 남겨도 된다. 기존 진행 중 작업을 새 큐로 자동 이관하지 않는다.
3. 업무 DB의 스키마 소유자로 `db/migrations/20261024_durable_ai/001_commands.sql`을 적용한다.
   SQL은 기존 데이터·테이블을 초기화하지 않는다. 마지막 GRANT의 `axox_app_rw`를 실제 런타임
   역할과 대조한다. AI의 체크포인트 DB에 적용하는 SQL이 아니다.
4. BE에 `AX_RECOVERY_ENABLED=true`, AI에 `AGENT_WORKER_ENABLED=true`를 설정해 재시작한다.
   BE를 먼저 전환하면 새 명령은 큐에서 대기한다. 이어 AI를 전환한다. 기존 실행을 비우지
   않고 전환하면 새 fencing 정책이 레거시 콜백을 거부하므로 이 순서를 생략하지 않는다.
5. 기존 `INTERNAL_TOKEN`, AI→BE 내부 URL, AI의 `AGENT_DATABASE_URL`을 유지한다.
   체크포인트 DSN은 transaction-pooling 프록시가 아닌 세션을 유지하는 PostgreSQL 연결이어야 한다.
6. VM Compose에서 Agent의 `stop_grace_period`를 45초 이상으로 설정한다.
   기본 Worker drain 30초보다 길어야 정상 종료 대기를 활용할 수 있다. 강제 종료 시에도 임대 만료로 복구한다.
7. 분석→승인→등록, PDF 색인을 확인하고 아래 상태 조회와 오류 로그를 점검한 뒤 접수를 재개한다.

설정 기본값: BE 임대 90초 / 총 시도 5회 / PDF 24시간 / 명령 이력 7일.
AI 동시 실행 1개 / 폴링 2초 / 종료 대기 30초. `AGENT_ENABLED`는 레거시 push 경로용이며
복구 모드에서는 push 디스패처를 건너뛴다. 플래그를 혼용한 장기 운영은 지원하지 않는다.

롤백은 새 접수를 멈추고 READY·LEASED 명령과 실행을 비운 뒤 두 플래그를 false로 돌린다.
기존 큐가 남은 상태에서 레거시로 전환하지 않는다. 테이블 삭제는 롤백에 필요하지 않다.
그래프 구조나 체크포인트 형식을 바꾸는 후속 배포는 별도의 호환성 검토가 필요하다.

## 관측과 검증

payload·원본·임대 토큰을 출력하지 않는 상태 집계:

```sql
SELECT kind, state, count(*) FROM ai_commands GROUP BY kind, state ORDER BY kind, state;
SELECT count(*) AS temporary_pdfs, coalesce(sum(octet_length(content)), 0) AS temporary_bytes
FROM ai_command_inputs;
SELECT command_id, kind, attempts, last_error FROM ai_commands WHERE state='DEAD';
```

`COMMAND_POLL_FAILED`는 BE 연결/플래그/인증을, `COMMAND_FAILED`는 재시도 또는 입력 오류를,
`COMMAND_STOPPED`는 처리 권한·체크포인트 연결 상실을 확인한다.

- 기본 Java 테스트·bootJar 빌드.
- `RECOVERY_TEST_JDBC_URL`을 지정한 실제 PostgreSQL 큐 테스트: 롤백, 동시 수령, 인계,
  stale 쓰기 차단, 승인 경합, 재시도 한도, PDF 삭제/만료. CI에도 PostgreSQL 서비스를 추가했다.
- 대응 AI 테스트는 실제 프로세스 SIGKILL 후 새 Worker·PostgresSaver에서 재개하고 승인 대기를 보존한다.
- 별도 로컬 BE + Business MCP 계약 테스트는 저장 응답 유실을 주입해 평가·로드맵·프로젝트 중복 방지와 PDF 삭제를 검증한다.

보장은 **동일 작업의 재실행을 허용하고 업무 결과 중복을 막는 것**이다. 실행 중이던 노드의
LLM·임베딩 호출은 반복되어 추가 비용이 생길 수 있다. SSE 연결 자체는 재시작 때 끊기며
기존 FE 재연결·상태 조회로 복구한다. 중간 TOKEN 이벤트 전체 재생은 제공하지 않는다.
단일 VM·DB 장애 중에는 처리가 대기하며, 이 PR은 서버/DB 고가용성을 제공하지 않는다.

## 이번 변경의 실행 결과 (2026-10-25)

| 실행 | 결과 |
| --- | --- |
| Java `clean test bootJar` + 실제 PostgreSQL 환경 | 76 통과, 기존 비활성 6건, 실패 0. 복구 큐 13건·내부 인증 2건 포함. |
| AI 전체 단위/계약 + 실제 PostgreSQL/SIGKILL | 202 통과, 조건부 29건 생략, 실패 0. |
| 별도 실제 BE + Business MCP + AI Worker 계약 | 1 통과. 평가 저장·프로젝트 저장 직후 응답 유실 후 평가 1개·로드맵 1개·프로젝트 3개 유지, PDF 입력 행 삭제 확인. |

검증에는 새 일회용 로컬 PostgreSQL 17과 시드 업무 DB를 사용했다. 운영 Cloud SQL·VM은
변경하지 않았다. 기존 Docker/Testcontainers 전체 `integrationTest`는 Docker daemon이
실행되지 않아 수행하지 않았고, 실제 외부 LLM·임베딩·pgvector API는 이 검증에 포함하지 않았다.
새 테스트는 기존 v3 fixture로 LLM/RAG 응답을 대체하며 실제 DB·체크포인트·BE 저장 경로를 검증한다.
