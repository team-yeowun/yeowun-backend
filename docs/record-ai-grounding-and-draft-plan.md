# 기록 AI 고도화 계획 — ① 질문 그라운딩(RAG) · ② Redis 임시저장(뒤로가기 복원) · ③ AI 응답 지연 개선

> 상태: **구현 완료(2026-07-20) — 빌드·전체 테스트(346건, 실패 0) 그린. 커밋/PR 미실행(승인 대기)**.
> 확정 결정: D-3=**Redis maxmemory 64mb**(보수적), D-4=**Redis 단일 어댑터(로컬·테스트 포함, 인메모리 폴백 없음)**, D-1·D-2·D-5·D-6=기본값.
> 이슈③ 확정: 모델=**Sonnet 5 유지**(프로덕션 무변경, **로컬 기본값만 Opus→Sonnet 5 교정**), 이번 작업에 **M-1+M-2+M-4 포함**(M-3 SSE·M-5 retry는 Phase 2).
> 대상 브랜치: `develop` 기준 신규 작업 브랜치
> 작성 배경: 베타 피드백 3건
> - **이슈 1**: "AI 전시 학습 데이터 기반 고도화 — AI가 전시랑 상관없는 질문을 한다"
> - **이슈 2**: "AI 질문 기반 전시 기록 작성에서 뒤로가기 누르면 되돌아 가는 이슈 수정"
> - **이슈 3**: "AI 응답 속도가 느린 경우가 많다"(compose ~6s, reminds ~4.7s — 관측 대시보드)

---

## 0. 한눈에

| 이슈 | 근본 원인 | 해결 방향 | 계층 |
|---|---|---|---|
| ① 엉뚱한 질문 | 프롬프트에 전시 근거가 **제목+장소+카테고리** 정도로 얇음(작가·설명은 원천 API가 자주 미제공). 이미 있는 전시 필드도 미사용, "구체 요소 참조" 제약도 없음 | 전시 메타데이터 **전부 활용** + **과거 기록 감정 집계(retrieval)**로 근거 주입 + 프롬프트에 "구체 요소 필수 참조·일반 질문 금지" 강제 | 백엔드 |
| ② 뒤로가기 시 상태 소실 | AI 플로우가 **서버 상태 0**(저장 시점에만 Record 생성). 질문은 매 호출 **재생성(비결정적)**이라 뒤로 갔다 오면 질문·답변이 날아감 | 질문 생성/답변을 **Redis에 캐싱** → 신규 `GET .../draft`로 **PULL 복원**. Redis 신규 배선 | 백엔드(복원 API) + 프론트(뒤로가기 시 GET 호출) |

두 이슈 모두 진입점은 기존 `RecordAiFacade` / `RecordAiV1Controller`. 저장(감상문 확정)은 기존 `POST /api/v1/records`가 그대로 담당한다(이번 범위 밖).

---

## 1. 현재 상태 진단

### 1.1 AI 질문 플로우(현행)
- `POST /api/v1/records/ai/questions` → `RecordAiFacade.questions()` → 전시 상세 조회 → LLM 구조화 출력(질문 3개). "다른 질문 보기"=재호출(재생성).
- `POST /api/v1/records/ai/compose` → Q&A로 감상문 본문 생성. "다시 다듬기"=재호출.
- 둘 다 `aiExecutor` 풀에서 비동기, 사용자당 `AiRateLimiter` 쿨다운(429), **서버에 아무 상태도 남기지 않음**.

### 1.2 이슈 ① 근본 원인 (파일 근거)
- `RecordAiFacade.exhibitionContext()`([RecordAiFacade.java:84](../src/main/java/modi/backend/application/record/RecordAiFacade.java#L84))가 프롬프트에 넣는 값: `title`, `place`, `category`, `artists`, `description`.
- **미사용인데 이미 있는 필드**(`ExhibitionResult.Detail`): 장르 키워드(`keywords`), 전시 형태(`format`), `artistSummary`, 관람 시기(`startDate~endDate`), 지역(`region`).
- `artists`·`description`은 원천 API(한눈에보는문화정보)가 자주 미제공 → 많은 전시에서 AI가 쥔 근거가 **제목+장소+카테고리**뿐([ExhibitionResult.java:42](../src/main/java/modi/backend/application/exhibition/ExhibitionResult.java#L42) 주석).
- 시스템 프롬프트가 "이 전시에 어울리는" 수준의 약한 지시라, 근거가 얇으면 **아무 전시에나 통하는 일반 질문**으로 흐른다.

### 1.3 이슈 ② 근본 원인
- 서버가 질문/답변을 보관하지 않고, `/questions`는 **매번 다른 질문**을 생성(LLM 비결정적).
- 프론트가 뒤로가기 후 재진입 시 다시 `/questions`를 호출하면 **질문이 바뀌고 답변이 유실**된다. 사용자는 이를 "되돌아간다"로 체감.
- 저장소 현황: Redis는 **이 프로젝트에 전혀 배선돼 있지 않음**(build.gradle·compose·application.yaml·전 브랜치·gradle 클래스패스 모두 0건. 주석 4곳의 "나중에 Redis로 승격" 메모만 존재). → **이번에 신규 배선**한다.

---

## 2. 이슈 ① — 질문 그라운딩(RAG) 설계

> 전제: 임베딩/벡터 인프라 없음(MySQL 8.4 + JPA). 따라서 "시맨틱 RAG"가 아니라 **구조화 retrieval + 프롬프트 그라운딩**. 외부/유료 API 추가 호출 없음(DB 조회만) — 비용/레이트리밋 영향 없음.

### 2.1 전시 메타데이터 보강 (공통, 항상 적용)
`exhibitionContext()`에 아래를 추가 주입(있는 필드만):
- 장르 키워드(`keywords`), 전시 형태(`format`), 작가 요약(`artistSummary`), 관람 시기(`startDate~endDate`), 지역(`region`).
- 외부 텍스트 가드(`UNTRUSTED_DATA_GUARD`)·설명 길이 제한(500자)은 유지.

### 2.2 과거 기록 기반 감정 집계 (retrieval)
"이 전시를 기록한 다른 관람객이 자주 남긴 감정"을 근거로 주입한다.
- 신규 쿼리(`RecordJpaRepository`):
  - `findTopEmotionCodesByExhibitionId(exhibitionId, Pageable)` — 같은 전시 기록의 `emotionCode`를 빈도순 집계(soft-delete 제외).
  - `findTopEmotionCodesByCategory(category, Pageable)` — 같은 전시 기록이 부족할 때 **카테고리 폴백**.
- Facade가 상위 N개(예: 6개)를 뽑아 프롬프트에 `이 전시에서 관람객들이 자주 느낀 감정: 벅참, 먹먹함, …` 형태로 넣고, **"감정을 단정하지 말고 질문의 방향(감정 영역) 힌트로만 써라"**를 지시.
- **콜드스타트**(기록 0개): 감정 근거 생략, 2.1 메타데이터만으로 질문 생성.

### 2.3 프롬프트 강화 (questions 시스템 프롬프트)
- 각 질문은 **이 전시의 구체 요소(제목/장르/작가/시기/감정 영역) 중 하나를 반드시 반영**.
- **아무 전시에나 통하는 일반 질문 금지**("이 전시 어땠나요?" 류 배제).
- 근거가 얇으면 **제목·장르에 앵커링**(없는 사실 지어내기 금지).

### 2.4 compose는 그라운딩 최소화
`compose()`는 사용자 답변에 충실해야 하므로(현행 "답변에 없는 사실 지어내지 마" 유지) 타 관람객 감정을 **주입하지 않음**. 전시 메타데이터 보강(톤 참고)만 적용.

### 2.5 프라이버시 원칙 (중요)
- 근거로 쓰는 것은 **집계된 감정 코드**(비식별)뿐. **타 사용자의 감상문 원문(content)은 프롬프트에 넣지 않는다**(여운=비공개 감상이 핵심 가치).
- 감정 집계는 사용자 식별 불가능한 상위 빈도 목록만.
- 👉 정렬 필요: "익명 감상문 발췌까지 근거로 넣을지"는 **Open Decision D-1** 참고(기본=넣지 않음).

---

## 3. 이슈 ② — Redis 임시저장(draft) 캐시 설계

### 3.1 복원 시나리오
```
전시 선택 → [POST questions] 질문 3개 생성(→서버가 draft에 질문 캐싱)
          → 사용자가 Q1~Q3 답변(프론트가 [PUT draft]로 답변 캐싱; 뒤로가기 대비)
          → [POST compose] 감상문 초안(→서버가 draft에 질문+답변+초안 캐싱)
          → (뒤로가기/재진입) 프론트가 [GET draft?exhibitionId=]로 질문+답변+초안 복원
          → 사용자 확정 → [POST /records]로 저장 → (선택) [DELETE draft]
```
- **프론트 연동 필수**: 백엔드는 "복원 가능"을 제공. 실제 뒤로가기 UX 수정은 프론트가 재진입 시 `GET draft`를 호출해 상태를 채우는 것으로 완성된다(정렬 필요 — 프론트 이슈로 별도 티켓 연결).

### 3.2 캐시 키 / 값 / TTL
- **키**: `ai:draft:{userId}:{exhibitionId}` — 사용자·전시당 진행 중 draft 1개(플로우와 1:1).
- **값(JSON)**: `AiDraft { List<String> questions, List<Qna{question,answer}> answers, String content, updatedAt }`.
- **TTL**: 기본 1시간(`app.ai.draft.ttl`, 설정 가능). 작성 세션 유지엔 충분, 방치 draft는 자동 만료.
- **본인만 접근**: 인증 userId로 키를 구성 → 남의 draft 조회 불가.

### 3.3 API 엔드포인트
| 메서드 | 경로 | 동작 | 비고 |
|---|---|---|---|
| POST (기존) | `/api/v1/records/ai/questions` | 질문 생성 **+ draft에 질문 캐싱** | 기존 응답 유지 |
| POST (기존) | `/api/v1/records/ai/compose` | 감상문 생성 **+ draft에 질문+답변+초안 캐싱** | 기존 응답 유지 |
| **PUT (신규)** | `/api/v1/records/ai/draft` | 진행 중 상태 저장(`{exhibitionId, questions, answers, content?}`) | 뒤로가기 전 프론트 자동저장용. LLM 호출 아님 → **레이트리밋/aiExecutor 미적용**(동기) |
| **GET (신규)** | `/api/v1/records/ai/draft?exhibitionId=` | draft 복원 → `{exists, questions, answers, content}` | 없으면 `exists=false` |
| DELETE (선택) | `/api/v1/records/ai/draft?exhibitionId=` | draft 삭제 | 저장 완료/포기 시. 없으면 TTL이 정리 |

- 전부 로그인 전용(`@Authentication LoginUser`). 응답은 기존 `ApiResponse` 규약. 상태코드 성공 200(컨벤션 유지, 201/PATCH 미사용).
- 페이로드 상한: `answers` 각 answer ≤ 300자, `content` ≤ 300자(기존 규칙 재사용).

### 3.4 포트/어댑터 구조 (기존 `RefreshTokenStore` 패턴 준수)
- 포트: `domain/ai/AiDraftStore`(순수 자바) + 값 `domain/ai/AiDraft`(record).
  - `void save(Long userId, Long exhibitionId, AiDraft draft)`
  - `Optional<AiDraft> find(Long userId, Long exhibitionId)`
  - `void delete(Long userId, Long exhibitionId)`
- 어댑터: `infra/ai/redis/RedisAiDraftStore`(@Component) — `StringRedisTemplate` + Jackson JSON, TTL set.
- **Redis 단일(D-4 확정)**: 인메모리 폴백 어댑터를 **두지 않는다**. 로컬은 `spring-boot-docker-compose`가 redis 자동기동, 통합테스트는 Testcontainers-Redis, 단위테스트는 포트를 mock. 즉 실어댑터는 `RedisAiDraftStore` 하나뿐.
- **DIP 유지**: Facade는 `AiDraftStore` 포트만 의존.

### 3.5 Redis 신규 배선
- **의존성**(build.gradle): `implementation 'org.springframework.boot:spring-boot-starter-data-redis'` (Lettuce, Boot BOM 관리 버전 — 1st-party 스타터라 Boot 4.1 호환 안전).
- **compose.yaml**: `redis:7-alpine` 서비스 추가(mysql 스타일 미러). **휘발성 캐시 전용** — 영속화 off + 메모리 상한 + LRU 축출로 **1GB EC2 보호**:
  ```yaml
  redis:
    image: 'redis:7-alpine'
    container_name: modi-redis
    logging: *default-logging
    command: ['redis-server','--save','','--appendonly','no','--maxmemory','64mb','--maxmemory-policy','allkeys-lru']
    ports: ['6379:6379']
    healthcheck:
      test: ['CMD','redis-cli','ping']
      interval: 10s
      timeout: 3s
      retries: 10
  ```
  `app` 서비스에 `depends_on: redis(healthy)` + `SPRING_DATA_REDIS_HOST=redis` 추가.
  (로컬 `bootRun`은 `spring-boot-docker-compose` 통합이 redis 서비스를 자동 기동·자동 연결.)
- **application.yaml**: `spring.data.redis.{host,port,password,timeout}`를 `${…:기본값}` 폴백으로 추가.
- **설정 프로퍼티**: 신규 `AiDraftProperties`(`app.ai.draft.ttl`=Duration 기본 1h) — `AiProperties`는 손대지 않음(기존 positional 생성자 테스트 깨짐 방지). `AiConfig`의 `@EnableConfigurationProperties`에 등록.
- **배포 env**: GitHub Actions deploy가 `.env`에 `SPRING_DATA_REDIS_HOST/PORT/PASSWORD` 기록(정렬 필요 — CI secret).

### 3.6 장애 시 graceful degradation
- draft 캐시는 **부가 기능**(핵심 저장 경로 아님). Redis 장애 시 어댑터가 예외를 삼켜 `save`=no-op, `find`=`empty` 반환 → **질문/감상문 생성·저장은 정상 동작**(복원만 일시 불가). Lettuce 지연 연결이라 Redis 미기동이어도 앱 부팅은 정상.

---

## 4. 아키텍처 컨벤션 준수 체크
- 상태변경은 Entity 메서드 안에서만 — draft는 캐시(비영속)라 Record 엔티티 규칙과 무관. ✅
- Facade=조율만: `RecordAiFacade`가 `ExhibitionFacade`(조회)+`RecordJpaRepository`(감정 집계 조회)+`AiChatClient`(LLM)+`AiDraftStore`(캐시)+`AiRateLimiter` 조합. 의존 5개 = "비대 신호" 경계 → 필요 시 감정 집계를 별도 컴포넌트로 분리(**Open Decision D-2**). ✅/⚠️
- 변환 흐름: `Request →[Controller] Criteria → Facade → Result →[Controller] Response`. draft 신규 DTO도 이 규약대로(`RecordAiDto` 중첩 record / `RecordAiCriteria`·`RecordAiResult` 확장). ✅
- 포트/어댑터: `AiDraftStore`(domain) + `RedisAiDraftStore`(infra) = DIP, 기존 `RefreshTokenStore` 미러. ✅
- API: 복수형·케밥·성공 200·PATCH 미사용·`@Authentication` 주입·Swagger는 `RecordAiV1ApiSpec`에. ✅
- 예외: `AiErrorCode`(+필요 시 draft 관련 코드) 통해서만. ✅

---

## 5. 변경 / 신규 파일 목록

### 신규
| 파일 | 목적 |
|---|---|
| `domain/ai/AiDraftStore.java` | 캐시 포트 |
| `domain/ai/AiDraft.java` | draft 값(record: questions/answers/content) |
| `infra/ai/redis/RedisAiDraftStore.java` | Redis 어댑터(JSON+TTL, graceful degrade) |
| `config/AiDraftProperties.java` | `app.ai.draft.ttl` |
| `docs/sequence-diagram/record/11-AI-임시저장-복원.md` | 신규 플로우 다이어그램 |

### 수정
| 파일 | 변경 |
|---|---|
| `build.gradle` | spring-boot-starter-data-redis 추가 |
| `compose.yaml` | redis 서비스 + app depends_on/env |
| `src/main/resources/application.yaml` | `spring.data.redis.*` |
| `config/AiConfig.java` | `AiDraftProperties` 등록 |
| `application/record/RecordAiFacade.java` | 메타데이터 보강 + 감정 집계 그라운딩 + 프롬프트 강화 + draft save/get/delete + 의존 추가 |
| `infra/record/RecordJpaRepository.java` | 감정 집계 쿼리 2종 |
| `application/record/RecordAiCriteria.java` | `DraftSave` 등 |
| `application/record/RecordAiResult.java` | `Draft` |
| `interfaces/record/dto/RecordAiDto.java` | `DraftSaveRequest`/`DraftResponse` |
| `interfaces/record/RecordAiV1Controller.java` | PUT/GET(/DELETE) draft |
| `interfaces/record/RecordAiV1ApiSpec.java` | Swagger 스펙 |
| `docs/sequence-diagram/record/09-AI-질문-생성.md` | 그라운딩·캐싱 반영 |
| `docs/erd.md` | Redis draft 캐시 주석 |

### 이슈③ 추가 변경
| 파일 | 변경 |
|---|---|
| `config/AiProperties.java` | `DEFAULT_CLAUDE_MODEL` Opus→**Sonnet 5**(로컬 기본값 교정, 프로덕션은 env로 이미 Sonnet) |
| `application/remind/RemindFacade.java` | 저장을 `PENDING` 즉시 반환 + 커밋 후 백그라운드 요약 트리거 |
| `application/remind/RemindAiSummarizer.java` | 백그라운드 실행(요약→Remind 갱신) 지원 |
| `domain/remind/RemindAiStatus.java` | `PENDING` 추가 |
| `domain/remind/Remind.java` | 요약 갱신 메서드(상태·요약 세팅) |
| compose/summary 호출부 | max-tokens 상한(M-4) |
| `docs/sequence-diagram/record/02-리마인드-저장.md`(remind) | 비동기 요약 반영 |

---

## 6. 테스트 계획
| 대상 | 방식 | 핵심 검증 |
|---|---|---|
| `RecordAiFacade` | Mockito 단위(기존 확장) | 프롬프트에 전시 메타+감정 집계 반영(ArgumentCaptor), questions/compose가 draft save 호출, getDraft 매핑, 콜드스타트 폴백 |
| `RecordJpaRepository` 감정 집계 | `@DataJpaTest` | 빈도순·soft-delete 제외·카테고리 폴백 |
| `RedisAiDraftStore` | Testcontainers-Redis 통합 | save→find→TTL 만료→delete, 연결 실패 시 degrade |
| draft 컨트롤러 | `@WebMvcTest` | PUT/GET/DELETE 계약, 인증 필요, exists=false |
| 회귀 | 기존 `RecordAiFacadeTest` 4건 | 시그니처 변경(신규 mock 주입) 후 그린 |

`./gradlew build` + `./gradlew test`(Testcontainers → Docker 데몬 필요) 통과를 완료 기준으로 한다.

---

## 7. 운영/배포 (1GB EC2 제약)
- Redis는 **휘발성 캐시**로만 사용 — `--save "" --appendonly no`(디스크 off) + `--maxmemory 64mb --maxmemory-policy allkeys-lru`(상한·자동 축출)로 메모리 폭주 차단. (기존 메모: 운영 1GB, 무거운 컨테이너 OOM 주의 → Redis는 경량·**64mb 상한**으로 안전 — D-3 확정)
- 배포 파이프라인의 Redis env(`SPRING_DATA_REDIS_*`) 주입 필요(CI secret).

---

## 8. 결정사항 (확정 2026-07-20)
| # | 결정 | 확정값 |
|---|---|---|
| **D-1** | RAG 근거에 익명 감상문 발췌 포함? | ✅ **미포함**(감정 집계만, 비공개 보호) |
| **D-2** | 감정 집계를 Facade 직접 vs 별도 컴포넌트 | ✅ **Facade 직접**(의존 5개, 경계선 — 커지면 분리) |
| **D-3** | Redis maxmemory 상한 | ✅ **64mb**(보수적) |
| **D-4** | 저장소 어댑터 전략 | ✅ **Redis 단일**(로컬·테스트까지, 인메모리 폴백 없음. 로컬=compose 자동기동, 통합테스트=Testcontainers-Redis, 단위=포트 mock) |
| **D-5** | PUT 자동저장 엔드포인트 | ✅ **포함**(뒤로가기 전 답변 보존) |
| **D-6** | 저장 완료 시 draft 삭제 | ✅ **DELETE 선택 제공**(+TTL) |
| **D-7** | 프론트 연동 | 프론트가 재진입 시 `GET draft` 호출(별도 티켓) |

---

## 9. 작업 순서 (승인 후)
1. **Redis 배선**: build.gradle → compose.yaml → application.yaml → `AiDraftProperties`/`AiConfig`. (부팅 확인)
2. **캐시 포트/어댑터**: `AiDraftStore`·`AiDraft`·`RedisAiDraftStore`(+graceful degrade).
3. **이슈 ② API**: Criteria/Result/DTO → Facade save/get/delete → Controller/ApiSpec(PUT/GET/DELETE) → questions/compose 캐싱 연결.
4. **이슈 ① 그라운딩**: `RecordJpaRepository` 감정 집계 쿼리 → `RecordAiFacade` 메타 보강·감정 주입·프롬프트 강화.
5. **이슈 ③ 지연**: M-1(`AiProperties` 기본 모델 Opus→Sonnet 5) + M-4(compose/remind max-tokens 상한) + M-2(reminds `PENDING` 반환 + aiExecutor 백그라운드 요약: `RemindAiStatus.PENDING`·Remind 갱신 메서드·afterCommit 트리거).
6. **테스트**: 단위·DataJpaTest·Testcontainers-Redis·WebMvcTest + 기존 회귀 + reminds 비동기 경로.
7. **문서**: 시퀀스 다이어그램(신규 11 + 09 갱신, remind 02 갱신)·erd 주석.
8. `./gradlew build` · `./gradlew test` 그린 → (미리보기 검증) → **커밋/PR은 승인 후**.

> 결정 확정(2026-07-20): D-1~D-7 위 표대로. **착수 승인 시** 1→7 순서로 구현.

---

## 10. 이슈 ③ — AI 응답 지연 개선

### 10.1 진단 (관측 대시보드)
| 엔드포인트 | 평균 지연 | 성격 |
|---|---|---|
| `POST /api/v1/records/ai/compose` | ~6047ms | AI 감상문 생성. aiExecutor 비동기(서블릿 스레드는 반환)지만 **클라이언트는 전체 시간 대기** |
| `POST /api/v1/reminds` | ~4721ms | `RemindAiSummarizer`가 감정 변화 요약을 **서블릿 스레드에서 동기 호출**([RemindFacade.save](../src/main/java/modi/backend/application/remind/RemindFacade.java#L90)) → 응답 차단 + **Tomcat 워커 점유**(best-effort인데 크리티컬 패스) |

지배 요인 = **LLM 생성 시간**. 모델: 로컬 `AI_MODEL` 미설정 → **Opus 4.8**(최느림, `AiProperties` 기본값), 프로덕션 = `AI_MODEL=claude-sonnet-5`([deploy.yml](../.github/workflows/deploy.yml#L107)). max-tokens 1024. 작업(300자 감상문/요약/질문 3개)은 경량 → 상위 모델은 latency 과지출.

### 10.2 개선 방법 (효과순)
| # | 방법 | 효과 | 비용/리스크 |
|---|---|---|---|
| **M-1** | **모델 다운시프트**: record/remind AI를 **Haiku 4.5**(`claude-haiku-4-5-20251001`)로. 로컬 기본값(Opus)도 교정 | ★★★ 6s→~1-2s 예상. 설정만 | 짧은 창작 품질 검증 필요(설문 Q19/Q20 민감) |
| **M-2** | **reminds AI를 크리티컬 패스에서 제거**: 저장 즉시 `PENDING` 반환 + 요약은 백그라운드(aiExecutor)에서 채움(GET 시 노출). 기존 record-AI 비동기 패턴·`RemindAiStatus` 재사용 | ★★★ reminds 응답 ~4.7s→수십ms, 워커 점유 해소 | 요약이 즉시 응답에 없음(polling/GET) |
| **M-3** | **compose 스트리밍(SSE)**: 토큰을 흘려보내 첫 글자 <1s. "동기·인터랙티브" 의도와 부합 | ★★★ 체감 지연 급감 | 엔드포인트+클라 변경(Phase 2) |
| **M-4** | **max-tokens 상한 축소**(compose/summary ~400-512) | ★ 최악 생성시간 바운드 | 낮음 |
| **M-5** | **인터랙티브 retry/timeout 튜닝**: Anthropic 529 과부하 시 재시도가 초 단위 지연 유발. 대화형은 재시도↓·빠른 "다시 시도" 안내, 백그라운드(remind)는 durable 유지 | ★★ 스파이크 완화 | 낮음 |
| **M-6** | **워밍업**: 부팅 시 no-op 호출로 okhttp 콜드스타트 제거 | ☆ 첫 호출만 | 낮음 |

### 10.3 RAG(이슈①)와의 긴장 — 명시
이슈① 그라운딩은 프롬프트 입력 토큰을 **늘린다**(감정 집계·메타 보강) → TTFT 소폭 증가. 완화: 그라운딩을 **바운드**(감정 상위 ~6개, 설명 500자 절단) + **M-1 빠른 모델**로 상쇄. 순효과는 M-1이 압도(생성 토큰 시간이 입력보다 지배적).

### 10.4 확정 (2026-07-20)
- ✅ **모델 = Sonnet 5 유지**: 프로덕션 무변경(이미 `AI_MODEL=claude-sonnet-5`). **로컬 기본값만 Opus→Sonnet 5로 교정**(`AiProperties.DEFAULT_CLAUDE_MODEL`) → 로컬 6s→~3-4s, 프로덕션과 일치. (M-1은 Haiku 아닌 **Sonnet**으로 확정)
- ✅ **이번 작업 포함**: M-1(로컬 기본 모델 교정) + **M-2**(reminds 저장을 `PENDING` 즉시 반환 + aiExecutor 백그라운드 요약) + M-4(compose/summary max-tokens 상한).
- **Phase 2**: M-3(compose SSE), M-5(interactive retry 튜닝).
- ⚠️ **프론트 조율**: M-2로 reminds 응답에 요약이 즉시 없음 → 클라가 `PENDING` 표시 후 GET 폴링으로 채움(이슈②의 GET draft와 함께 프론트 티켓).

### 10.5 M-2 상세 (reminds 비동기화)
- 현행: `RemindFacade.save()`가 `summarizer.summarize()`를 **동기 호출** → 요약을 Remind에 담아 저장 후 응답(=응답이 AI를 기다림).
- 변경: Remind를 `RemindAiStatus.PENDING`(요약 null)로 **먼저 저장·커밋·응답** → 커밋 후 aiExecutor에서 요약 생성 → id로 Remind 로드해 요약·상태 갱신(별도 tx). `GET /reminds/{id}`가 완료 시 요약 노출.
- 필요 변경: `RemindAiStatus.PENDING` 추가 · `Remind`에 요약 갱신 메서드 · 커밋 후 백그라운드 트리거(예: `TransactionSynchronization` afterCommit 또는 save 후 executor 제출) · best-effort 실패 시 `FAILED`.
- **백필 스케줄러(추가)**: 백그라운드가 인메모리라 재시작 시 유실 → PENDING이 영구히 남을 수 있다.
  `RemindSummaryBackfillScheduler`가 주기(기본 10분)로 PENDING을 훑어 복구한다.
  유예(기본 2분) 이전 건만 대상(진행 중 작업과 중복 방지), 배치 상한(기본 20건)으로 유료 호출 제한,
  포기 시간(기본 1일) 초과 시 AI 호출 없이 `FAILED` 확정(클라 무한 폴링 방지), 쿨다운(SKIPPED)이면 PENDING 유지 후 재시도.
  설정 `app.remind.summary-backfill.*`(env로 전부 조정·비활성 가능), AI 미설정이면 조회조차 하지 않음(외부 호출 0).

### 10.6 곁다리 보안 노트
`.env`는 git-ignore되어 커밋 안 됨 ✅. 단 라이브 `ANTHROPIC_API_KEY`가 평문 로컬 파일 + 이 세션 로그에 노출 → 우려되면 콘솔에서 키 **회전** 권장(판단은 사용자).
