# 질문 답변과 새 분석 실행

브라우저 채팅은 `POST /api/ai/chat/messages`를 사용한다. `POST /api/ai/tasks`는 기존의 명시적인 분석 접수 API로 유지한다. 새 대화 API에는 별도의 DB 마이그레이션이 필요 없다.

```json
{
  "message": "아까 추천한 과제가 왜 1순위야?",
  "history": [
    {"role": "user", "content": "AX 성숙도가 뭐야?"},
    {"role": "assistant", "content": "기업의 AX 전환 준비 수준을 살펴보는 진단이에요."}
  ],
  "referenceTaskId": "67def8d2-034f-45b6-9976-e83628a904f3"
}
```

- JWT 필수. 회사/사용자 ID는 JWT에서만 얻는다.
- `message`는 공백 제외 1~4000자, `history`는 최대 12개·각 4000자·합계 16000자. history 역할은 user/assistant만 허용한다.
- `referenceTaskId`는 생략 가능하며 UUID다. 기존 `TaskService.chatContext`의 요청자/회사 검사를 통과한 작업만 AI에 제공한다. 클라이언트가 보낸 분석 결과/회사 ID는 컨텍스트로 사용하지 않는다.
- FE는 가장 최근 생성했거나 작업 이력에서 선택한 분석을 참조하고 입력창 위에 표시한다. 다른 분석은 작업 이력에서 선택한 뒤 질문한다. 새로고침 후 일반 대화 내용 복원은 이번 범위에 포함되지 않는다.

응답은 HTTP 200의 `{intent, message, taskId}`다.

| intent | 처리 | taskId |
| --- | --- | --- |
| GREETING | 인사와 지원 기능 안내 | null |
| SERVICE_QA | AX 개념·방법·서비스 사용법 설명 | null |
| RESULT_QA | 권한 확인한 기존 작업의 상태·결과 설명 | null |
| CLARIFY | 의도/필요 자료/참조 결과 확인 질문 | null |
| OUT_OF_SCOPE | 서비스 범위 안내 | null |
| ANALYZE | 기존 TaskService.create 트랜잭션으로 작업·실행 명령 생성 | 실제 생성한 taskId |

모델 실패/타임아웃/잘못된 응답은 `503 AI_UNAVAILABLE`이며 분석을 대신 실행하지 않는다. 분석 데이터가 아직 없으면 CLARIFY를 반환한다. AI 호출 동안 DB 트랜잭션을 열어두지 않는다. 기존 복구 큐의 활성화 설정·마이그레이션 요구사항은 그대로다.

FE는 taskId가 있는 ANALYZE에서만 SSE·분석 단계·승인 카드를 사용한다. 일반 대화는 별도 메시지로 표시한다. 분석 중에도 질문할 수 있지만, 답변 요청이 진행 중인 동안 중복 메시지 전송을 막는다. 승인과 데이터 확정은 기존 전용 API/카드에서만 처리한다.

## 배포 순서

AI → BE → FE 순서로 배포한다. 새 BE가 이전 AI에 연결되면 503으로 종료하며 분석으로 자동 전환하지 않는다. 운영 배포는 별도 실행한다.

## 확인 방법

인증 토큰을 환경 변수로 설정한 뒤 운영/로컬 BASE_URL에 아래 요청을 보낸다. 토큰을 문서나 공유 로그에 넣지 않는다.

```bash
curl --fail-with-body "$BASE_URL/api/ai/chat/messages" \
  -H "Authorization: Bearer $ACCESS_TOKEN" -H 'Content-Type: application/json' \
  --data '{"message":"안녕","history":[]}'
```

"안녕", "AX 성숙도가 뭐야?", "오늘 저녁 추천해줘", "우리 회사 좀 봐줘"에는 taskId가 없어야 한다. 요청 전후 `/api/ai/tasks?mine=true`의 작업 수가 같아야 한다. 분석 가능한 회사에서 "개발팀을 예산 2억으로 분석해줘"만 taskId와 새 작업 한 개를 반환해야 한다. 해당 작업을 참조한 설명 질문은 작업 수를 늘리지 않는다. 타 사용자/회사 작업 참조는 403이어야 한다.

검증: BE `cd demo && ./gradlew test`; AI의 mock/API·모델 출력 검증 테스트; FE `npm test && npm run lint && npm run build`. 실제 모델의 의미 분류는 AI의 opt-in 평가 테스트로 별도 확인한다. mock/구조 테스트 통과만으로 실모델 분류 정확도를 주장하지 않는다.
