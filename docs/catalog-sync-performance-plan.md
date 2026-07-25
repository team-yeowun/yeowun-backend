# 전시 수집(syncCatalog) 성능 — 먼저 재고, 그다음 배치 조회

작성일: 2026-07-21
상태: **미착수 (계획만)**
대상: `ingestion/application/CatalogSynchronizer#syncCatalog`
관련: `ExhibitionSyncFacade#archiveListSnapshot` · `ExhibitionBackfillFacade#findDetailTargetState` · `ExhibitionDraftFacade#stageFromList` · `ExhibitionOutboxFacade#enqueue`

## 0. 결론 먼저

1. **측정이 먼저다.** 지금 느리다는 근거가 없다. 시간·쿼리 수를 재고 나서 손댄다.
2. 손댄다면 **건당 DB 왕복을 배치 조회로** 접는다. 외부 호출은 이미 3콜이라 아낄 게 없다.
3. **"최신순으로 읽다가 아는 것 만나면 중단"은 전량 순회를 대체할 수 없다** — 신규는 잡지만 변경·소멸을 놓친다(4장).

---

## 1. 현재 무엇을 하고 있나

`syncCatalog`는 **전량 정합(full reconciliation)**이다. 원천 목록을 모두 받아 한 건씩 upsert 한다.

### 중복 방지는 `external_id` 기반 3중 장치

| 층 | 장치 |
|---|---|
| DB 제약 | `uk_exhibitions_external_id` · `uk_exhibition_draft_external` · `uk_culture_list_response_external_id` · 아웃박스 `UK(message_type, target_key)` |
| 애플리케이션 | 건마다 `findByExternalId` 후 삽입/갱신 분기 |
| 변경 감지 | `CultureListSnapshot#seenAgain` — `lastSeenAt`은 매번 갱신하되 **payload 해시가 달라졌을 때만** 필드를 덮는다(같은 값으로 매일 덮으면 "언제 바뀌었나"를 잃는다) |

같은 전시를 100번 sync 해도 행은 하나다. 매 실행은 삽입이 아니라 upsert다.

---

## 2. 비용 구조 — 외부 호출이 아니라 DB다

### 외부 호출: 실측 3콜

2026-07-21 기준 원천 전시 **266건**, `page-size=100` → `ceil(266/100)` = **3콜**. 무시할 수준이고, 정렬을 바꿔도 줄지 않는다.

### DB: 건당 최소 5~7 왕복

목록 1건이 루프를 통과할 때 실제로 나가는 쿼리(코드 확인 기준):

| 단계 | 쿼리 |
|---|---|
| `archiveListSnapshot` | `culture_list_snapshot` select 1 + insert/update 1 |
| `findDetailTargetState` | `exhibitions` select 1 + `hasDetail` select 1 |
| `stageFromList` (신규·갱신 경로) | `exhibition_draft` select 1 + save 1 |
| `outbox.enqueue` | `outbox` select 1 + insert 1 |

**266건 × 5~7 ≈ 1,300~1,800 쿼리/회.** 이게 실제 부하 지점이다.

> 정확한 수는 경로마다 다르다(ALREADY_SYNCED면 draft·outbox 단계를 건너뛴다). 그래서 **추정하지 말고 측정한다**(3장).

---

## 3. 1단계 — 측정 (먼저 할 일)

손대기 전에 아래를 확보한다.

### 무엇을 재나

- `syncCatalog` 전체 소요 시간 (트리거별: BOOT / SCHEDULE / MANUAL)
- 그중 **외부 호출 시간** vs **DB 시간** 비율
- 실행당 총 쿼리 수, 테이블별 분해
- 건당 평균 처리 시간, 경로별 분포(SKIPPED / STAGED / REFRESHED / BACKFILLED)

### 어떻게 재나

- 이미 있는 것을 먼저 쓴다 — `sync_run` 테이블에 `started_at`·`finished_at`과 집계 컬럼(`collected`·`inserted`·…)이 있다. **총 소요 시간은 지금도 질의 가능하다.**
- 쿼리 수는 개발 환경에서 하이버네이트 통계나 데이터소스 프록시로 카운트한다(운영 상시 활성화는 하지 않는다).
- 외부 호출 시간은 `external_api_call` 감사 행으로 분리할 수 있다(호출마다 `called_at`이 남는다).

### 판단 기준

- 스케줄 주기 대비 소요 시간이 **여유롭다면 최적화하지 않는다.** 지금 규모(266건)에서는 그럴 가능성이 높다.
- 최적화에 들어갈 기준선: 소요 시간이 주기의 상당 비율을 먹거나, 원천 건수가 수천 건대로 늘어난 시점.

---

## 4. 검토했으나 채택하지 않은 안 — "최신순 조기 종료"

> 정렬해서 1번부터 읽다가 이미 DB에 있는 게 보이면 중단하면 되지 않나?

패턴 자체는 유효하다(증분 동기화). 다만 두 가지를 짚어야 한다.

### 4-1. 정렬 키가 등록 순서여야 한다

`START_DATE_DESC`(sortStdr=4)로는 **성립하지 않는다.** 오늘 등록된 전시가 지난달에 시작한 전시일 수 있고, 시작일 정렬에서는 그게 목록 중간에 꽂힌다 — 앞에서 멈추면 그 신규 항목을 영영 못 본다.

조기 종료가 성립하려면 원천의 **등록 순서와 단조**인 키여야 한다. 실측상 `REGISTRATION_DESC`(sortStdr=8, seq 내림차순)가 그것이다. 즉 필요한 건 "최신순"이 아니라 **"최근 등록순"**이다.

### 4-2. 그래도 전량 순회를 대체할 수 없다

조기 종료가 놓치는 건 신규가 아니라 **변경과 소멸**이다.

| 사건 | 조기 종료로 잡히나 | 이유 |
|---|---|---|
| 신규 등록 | ✅ | 목록 앞쪽 |
| 기존 전시 제목·기간 정정 | ❌ | seq가 그대로라 목록 깊숙이 있음 |
| 원천에서 사라짐 | ❌ | 없는 걸 알려면 전량 열거가 필요(`last_seen_at`) |
| 유실된 아웃박스 메시지 복원 | ❌ | 재sync 안전망(ADR-12)이 앞쪽에만 적용 |

두 번째는 가정이 아니라 실제 사례다 — `CultureVendorArchiveTest`에 "정정 전 제목 → 정정 후 제목" 케이스가 있고, `seenAgain()`의 해시 비교가 그걸 처리하려고 존재한다.

### 4-3. 쓴다면 "대체"가 아니라 "보완"

```
증분 패스 (자주)   sortStdr=8 + 아는 seq 만나면 중단   → 신규 등록을 빨리 잡는다
전체 정합 (드물게)  현재 방식 전량 순회                 → 변경·소멸을 잡는다
```

지금 도입하지 않는 이유:

- 아끼는 게 HTTP 3콜 → 1콜뿐이다
- "몇 개 연속으로 아는 걸 만나야 멈출지" 기준이 필요하다(첫 번째에서 멈추면 필터에 걸러진 항목 하나로 조기 종료된다)
- seq가 **항상** 등록 순서와 단조라는 가정에 의존하는데 **미검증**이다(원천이 과거 데이터를 나중에 채워 넣으면 깨진다)
- `sortStdr=8`은 원천 문서에 없는 값이다(실측으로만 확인)
- 전체 정합 주기를 따로 운영해야 한다

**원천이 수만 건이 되면 정확히 이 구조가 답이다.** 그때 다시 꺼낸다.

---

## 5. 2단계 — 배치 조회 최적화 (측정 후 착수)

건당 왕복을 **배치 1회 + 메모리 분기**로 접는다. 순회 대상은 어차피 전량이므로, 줄일 것은 "몇 건을 보느냐"가 아니라 "건당 몇 번 왕복하느냐"다.

### 방향

```
현재: for (item : 266건) { select ×4~5; save ×2~3 }
목표: select ×3~4 (external_id IN (...))  →  메모리에서 분기  →  일괄 save
```

- `culture_list_snapshot` / `exhibitions`(+detail 여부) / `exhibition_draft` / `outbox`를 각각 `WHERE external_id IN (:ids)` 한 번으로 읽어 `Map<String, ...>`으로 만든다
- 루프는 그 맵을 보고 분기만 한다(외부 호출 0은 지금과 동일)
- 쓰기는 JPA 배치 insert/update로 묶는다(`hibernate.jdbc.batch_size`)

### 주의할 점

- **IN 절 크기 상한** — 266건은 한 번에 되지만, 원천이 커지면 청크(예: 500건)로 쪼개야 한다
- **트랜잭션 경계** — `syncCatalog`는 지금 트랜잭션 밖 조율자다(enricher와 동형). 배치화하면서 통짜 트랜잭션으로 만들면 한 건 실패가 전체를 되돌린다 — **현재의 건별 실패 격리(`deferred` 카운트)를 잃지 않아야 한다**
- **아웃박스 멱등** — `enqueue`가 select 후 insert라 배치화 시 경합 창이 생긴다. UK(`message_type`, `target_key`)가 최후 방어선이므로 **제약 위반을 정상 흐름으로 흡수**해야 한다
- **`seenAgain`의 해시 비교 보존** — 배치 upsert로 바꾸면서 "값이 같으면 안 덮는다"를 잃으면 변경 시점 추적이 깨진다

### 검증

- 최적화 전후로 3장의 측정을 동일 조건에서 재실행해 비교한다
- 기존 테스트(`ExhibitionCatalogSyncTest`·`CultureVendorArchiveTest`)가 그대로 통과해야 한다 — 특히 "정정 전/후 제목" 케이스가 회귀 그물이다

---

## 6. 착수 순서 요약

1. `sync_run`으로 현재 소요 시간부터 확인 (질의만, 코드 변경 없음)
2. 개발 환경에서 쿼리 수 계측
3. 기준선 미달이면 **여기서 멈춘다** — 지금 규모에서는 이 결말이 유력하다
4. 초과하면 5장의 배치 조회 착수, 전후 비교로 효과 검증
