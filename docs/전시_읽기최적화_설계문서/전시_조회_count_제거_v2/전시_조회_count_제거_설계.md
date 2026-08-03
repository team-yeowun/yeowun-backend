# 🧮 전시 조회 `count(*)` 제거 — 문제 분석과 해결안

> 부하실험에서 **목록 지연의 대부분이 `count(*)`** 로 확인됐습니다. 이 문서는 그 count가
> **어디서 · 왜 · 어떤 원리로** 발생하는지 코드로 설명하고, 해결안을 before/after로 정리합니다.

**근거 실험** → [`3.전시_조회_부하실험_결과.md`](<../전시_조회_부하실험_v1(처음 구조)/3.전시_조회_부하실험_결과.md>) · [`4.성능진단_대시보드.html`](<../전시_조회_부하실험_v1(처음 구조)/4.전시_조회_성능진단_대시보드.html>)
**대상 코드** `551794f` (`feat/search-history`) · **작성** 2026-07-29

---

## 0. 왜 이걸 먼저 하는가

100만 건에서 **같은 필터에 정렬만 바꿔** 측정한 값입니다(직접 측정, 추정 아님).

| 필터 | 인기순 = **count가 지배하는 몫** | 최신순 − 인기순 = filesort 몫 | 합 |
| --- | ---: | ---: | ---: |
| 없음 | **5,382ms** | +3,508ms | 8,890ms |
| 곧 끝남 | **5,779ms** | +3,124ms | 8,903ms |
| 이번 달 개막 | **5,639ms** | +3,620ms | 9,259ms |
| 무료 | **11,238ms** | +4,186ms | 15,424ms |

**count 몫이 filesort 몫의 1.5~2.7배**입니다. 그리고 `totalCount`를 부르지 않는 배너(11ms)·상세(13ms)는 3,196배 볼륨 증가에도 평평했습니다.

> 🧠 **인덱스를 먼저 고쳐도 체감이 안 바뀝니다.** filesort를 없애면 노란 몫(3~4초)만 사라지고 붉은 몫(5.4~11.2초)이 그대로 남습니다.

---

## 1. count가 문제 되는 API 리스트업

### 🔴 A. 낭비되는 count — 만들고 그냥 버림

| API | 경로 수 | 무엇이 | 상태 |
| --- | ---: | --- | --- |
| `GET /api/v1/exhibitions` | **24** | `searchSlice`가 `findAll(spec, Pageable)`을 써서 **아무도 안 쓰는 count**를 한 번 더 실행 | 🔧 커밋 `459ae28`이 이미 해결(현재 브랜치 **미포함**) |

**해당 경로 전부** — 홈 3섹션(`section=ending-soon|free|opening-this-month`) · 탐색 4정렬(`latest|ending|popular|distance`) · 섹션×정렬 9조합 · 지역/분류 필터 · 검색 · 깊은 커서 전부.
목록 API 하나가 24경로이므로 **목록으로 들어오는 모든 요청**이 해당합니다.

### 🔴 B. 필요하지만 비싼 count — 설계 문제

| API | 무엇이 | 상태 |
| --- | --- | --- |
| `GET /api/v1/exhibitions` | `totalCount` 응답값을 만들려고 **조건에 걸린 66만 행을 매번 셈** | ❗ **미해결** — A를 고쳐도 남음 |

`count(*)`에는 `LIMIT`이 없습니다. **`size=2`인 홈 섹션도 2건을 보여주려고 50만 행을 셉니다.**

조건별 count 1회 비용(10만 볼륨 · 순수 SQL · warm):

| 조건 | 1회 | 무필터 대비 | 왜 |
| --- | ---: | ---: | --- |
| 필터 없음 | 123ms | 기준 | 진행 중 6.6만 행 전수 |
| `region=SEOUL` | 184ms | 1.5× | `exhibition_place` 서브쿼리 — **region 인덱스 없음** |
| `section=free` | 225ms | 1.8× | `exhibition_detail` 서브쿼리 — **price 인덱스 없음** |

### 🟢 C. count를 안 부르는 API — 건드릴 것 없음

| API | 100만 p50 | 배율 |
| --- | ---: | ---: |
| `GET /api/v1/exhibitions/{id}` | 13ms | 1.0× |
| `GET /api/v1/exhibitions/banners` | 11ms | 1.8× |
| `GET /api/v1/exhibitions/region-groups` | 2ms | — |

### 🟡 D. 같은 구조, 다른 축 — 이번 범위 밖이지만 기록

offset 페이지네이션이라 **응답 계약에 `totalElements`·`totalPages`가 들어 있어** count가 불가피한 곳입니다. 전시 볼륨이 아니라 **유저당 데이터 수**에 반응하므로 별도 실험 대상입니다.

- `application/record/RecordService.java` — 기록 목록 (`Page<RecordListItemResponse>`)
- `application/remind/RemindFacade.java` — 리마인드 목록 (`Page<RemindResult.ListItem>`)
- `application/admin/AdminUserFacade.java` · `ingestion/application/admin/IngestionAdminFacade.java` — 관리자 목록

> ⚠ **이번 실험은 이들을 측정하지 않았습니다.** "안전하다"고 쓸 수 없습니다.

---

## 2. 현재 코드 — 무엇이 어떻게 도는가

### 2-1. 진입점: 목록 1요청이 SQL 5개를 날린다

`application/exhibition/list/ExhibitionListService.java`

```java
@Transactional(readOnly = true)
public ExhibitionResult.ListPage search(ExhibitionCriteria.Search criteria) {
    LocalDate today = LocalDate.now(AppTime.KST);
    ExhibitionSort sort = ExhibitionSort.from(criteria.sort());
    int size = clampSize(criteria.size());
    // …
    Cursor cursor = Cursor.decode(criteria.cursor(), sort.code()).orElse(null);
    ExhibitionQuery query = queryFactory.create(criteria, sort, today,
            Cursor.keyOf(cursor), Cursor.lastIdOf(cursor));

    // ① 슬라이스 — 그런데 내부에서 count가 하나 더 나간다 (2-2 참조)
    List<Exhibition> rows = exhibitionQueryRepository.searchSlice(query, size + 1);
    boolean hasNext = rows.size() > size;
    List<Exhibition> page = hasNext ? rows.subList(0, size) : rows;

    // ② 조립 — 전시장 배치 조회 + 가격 배치 조회 (각 1쿼리, 페이지 크기에 비례하지 않음)
    List<ExhibitionResult.ListItem> content = listAssembler.assemble(page, today, criteria.requesterId());
    String nextCursor = hasNext ? encodeCursor(sort, page.get(page.size() - 1)) : null;

    // ③ totalCount — LIMIT 없이 조건 전체를 센다
    return new ExhibitionResult.ListPage(content, nextCursor, hasNext,
            exhibitionQueryRepository.count(query));
}
```

실제로 나가는 SQL:

```
GET /api/v1/exhibitions?sort=latest&size=20
  ├ count(*)             ← ①의 부작용. 아무도 안 씀       ⚠ 낭비
  ├ slice (LIMIT 21)     ← ①의 본체
  ├ count(*)             ← ③ totalCount 응답용
  ├ 전시장 배치 조회      ← ②
  └ 가격 배치 조회        ← ②
```

### 2-2. 낭비되는 count가 생기는 지점

`infra/exhibition/catalog/ExhibitionQueryRepositoryImpl.java`

```java
@Override
public List<Exhibition> searchSlice(ExhibitionQuery query, int limitPlusOne) {
    return jpaRepository.findAll(ExhibitionSpecifications.slice(query),
            PageRequest.of(0, limitPlusOne, sortFor(query.sort()))).getContent();
    //                                                              ~~~~~~~~~~~~
    //  Page를 받아 content만 꺼내 쓰고, 함께 실행된 총 건수는 버린다
}
```

`findAll(Specification, Pageable)`은 **`Page`** 를 돌려줍니다. `Page`는 `getTotalElements()`를 제공해야 하므로 **총 건수 count를 함께 실행**합니다. 우리는 `getContent()`만 쓰고 그 값을 버립니다.

### 2-3. 동작 원리 — 왜 작은 데이터에선 안 보였나

Spring Data JPA의 `SimpleJpaRepository.findAll(spec, pageable)`은 결과를 `PageableExecutionUtils.getPage(content, pageable, countSupplier)`로 감쌉니다. 이 유틸이 **count를 생략할 수 있으면 생략**합니다:

```
if (첫 페이지다  &&  요청한 페이지 크기 > 실제로 받은 건수) {
        // 다음 페이지가 없는 게 확실하다 → 총 건수 = 받은 건수
        return new PageImpl<>(content, pageable, content.size());   // ← count 안 나감
}
return new PageImpl<>(content, pageable, countSupplier.getAsLong()); // ← count 나감
```

우리 호출은 `PageRequest.of(0, size + 1)`이라 **항상 첫 페이지**입니다. 그래서 판정은 뒤 조건 하나로 갈립니다.

| 상황 | `pageSize` | 받은 건수 | `pageSize > content.size()` | count |
| --- | ---: | ---: | :---: | :---: |
| 데이터 적음 (다음 페이지 없음) | 21 | 8 | ✅ 참 | **생략** |
| **페이지가 꽉 참 (다음 페이지 있음)** | 21 | **21** | ❌ 거짓 | **실행** |

> 💥 **그래서 실사용에서만 드러납니다.** 로컬에 데이터가 몇 건 있을 땐 count가 한 번만 나가서 정상으로 보이고, 목록이 한 페이지를 채우는 순간(= 다음 페이지가 있는 정상 상태) 두 번이 됩니다.
> 운영은 진행 중 전시가 246건에 `size=20`이라 **거의 모든 목록 요청이 페이지를 채웁니다.**

### 2-4. count 자체가 비싼 이유

`infra/exhibition/catalog/ExhibitionSpecifications.java`의 `filter()`가 만드는 조건에는 `LIMIT`이 붙지 않습니다. 슬라이스는 21건만 보고 멈출 수 있지만 **count는 조건을 만족하는 행을 전부 세야 합니다.**

여기에 현재 인덱스 상황이 겹칩니다 — 100만 건 `EXPLAIN ANALYZE`:

```
-> Index lookup on e using idx_exhibitions_type_owner (type='CATALOG')
                     (cost=55834 rows=465384) (actual time=0.713..1959 rows=1e+6)
```

`type='CATALOG'`가 **전체 행의 100%** 라 이 인덱스는 이름만 인덱스이고 실제로는 풀스캔입니다. 거기에 `section=free`면 `exhibition_detail` 서브쿼리가, `region`이면 `exhibition_place` 서브쿼리가 얹힙니다(둘 다 해당 컬럼에 인덱스 없음).

---

## 3. 해결안 6가지 (after 코드 포함)

### 해결 1 — 버리는 count 제거 ⭐ 즉시 적용

**커밋 `459ae28`이 이미 이 내용입니다**(브랜치 `refactor/enhance-exhibtion-read`, 현재 브랜치 미포함).

**Before**
```java
@Override
public List<Exhibition> searchSlice(ExhibitionQuery query, int limitPlusOne) {
    return jpaRepository.findAll(ExhibitionSpecifications.slice(query),
            PageRequest.of(0, limitPlusOne, sortFor(query.sort()))).getContent();
}
```

**After**
```java
/**
 * 키셋 한 페이지 — <b>count 없이</b> 목록만 가져온다.
 * findAll(spec, Pageable)은 Page를 만들며 총 건수 count를 함께 실행한다(우리는 그 값을 버린다).
 */
@Override
public List<Exhibition> searchSlice(ExhibitionQuery query, int limitPlusOne) {
    return jpaRepository.findBy(ExhibitionSpecifications.slice(query),
            fluent -> fluent.sortBy(sortFor(query.sort())).limit(Math.max(1, limitPlusOne)).all());
}
```

`findBy(spec, fluent -> …)`는 **`List`를 돌려주므로 총 건수가 필요 없습니다.** 정렬과 상한만 걸립니다.

| | 값 |
| --- | --- |
| 효과 | SQL 5개 → **4개**, count 2회 → **1회** |
| 예상 개선 | 목록 경로 **~40%**(count 몫의 절반) |
| 리스크 | 없음 — 응답 계약 변화 0 |
| 회귀 방지 | `ExhibitionSliceCountTest`가 Hibernate 통계로 실행된 JDBC 문 수를 셈. **대상을 페이지 크기보다 많이 만들어** 페이지를 반드시 채움(안 그러면 옛 구현도 통과) |

---

### 해결 2 — `totalCount`를 응답에서 뺀다 ⭐⭐ 가장 근본적

커서 페이지네이션은 원래 총 건수가 필요 없습니다. `hasNext`만으로 무한 스크롤이 됩니다.

**Before** — `interfaces/common/dto/CursorResponse.java`
```java
public record CursorResponse<T>(List<T> content, String nextCursor, boolean hasNext, long totalCount) { }
```

**After**
```java
public record CursorResponse<T>(List<T> content, String nextCursor, boolean hasNext) { }
```

`ExhibitionListService.search`에서 `exhibitionQueryRepository.count(query)` 호출과 `ExhibitionResult.ListPage`의 `totalCount` 필드를 함께 제거합니다.

| | 값 |
| --- | --- |
| 효과 | SQL 4개 → **3개**, count **0회** → 목록이 배너·상세처럼 평평해질 가능성 |
| 예상 개선 | **가장 큼** — 붉은 몫이 통째로 사라짐 |
| 리스크 | **프론트 계약 변경.** "전체 246건" 같은 표시가 있으면 UX 결정 필요 |
| 선행 확인 | 프론트가 `totalCount`를 **어디에 쓰는지** 먼저 확인할 것 |

---

### 해결 3 — `totalCount`를 별도 요청으로 분리

목록은 즉시 주고, 건수는 뒤따라 채웁니다. 체감이 **목록 속도로 결정**됩니다.

**After** — 새 엔드포인트
```java
/** 같은 필터의 총 건수만. 목록과 분리해 체감 지연에서 뺀다. */
@GetMapping("/count")
public ResponseEntity<ApiResponse<ExhibitionDto.CountResponse>> count(
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false) String section,
        /* … 목록과 동일한 필터 파라미터 … */) {
    long total = exhibitionFacade.count(criteria);
    return ResponseEntity.ok(ApiResponse.success(new ExhibitionDto.CountResponse(total)));
}
```

| | 값 |
| --- | --- |
| 효과 | 목록 체감은 해결 2와 동일. 총 건수는 유지 |
| 리스크 | 요청 1개 추가. 필터 파라미터가 두 곳에 중복돼 **동기화 실수 여지** |
| 적합 | 프론트가 총 건수를 꼭 써야 하는데 즉시일 필요는 없을 때 |

---

### 해결 4 — 상한 있는 근사 count ("1,000+")

정확한 숫자 대신 상한까지만 셉니다. 스캔량이 상한에서 멈춥니다.

**After** — 포트에 상한 인자를 추가
```java
/** 최대 {@code limit}까지만 센다. 그 이상이면 limit을 반환한다(= "limit+"). */
long countUpTo(ExhibitionQuery query, int limit);
```

```java
// Impl — 서브쿼리로 상한을 건다
@Override
public long countUpTo(ExhibitionQuery query, int limit) {
    return jpaRepository.findBy(ExhibitionSpecifications.filter(query),
            fluent -> fluent.project("id").limit(limit).all()).size();
}
```

응답은 `totalCount: 1000, totalCountExact: false`처럼 내려 프론트가 `1,000+`로 표시합니다.

| | 값 |
| --- | --- |
| 효과 | 스캔이 `limit`에서 멈춤 — **볼륨과 무관하게 상수 시간** |
| 리스크 | 숫자가 정확하지 않음. UI 문구 변경 필요 |
| 적합 | "몇 건인지 대충 알면 되는" 화면 |

---

### 해결 5 — count 캐시

필터 조합을 키로 캐시하고 TTL을 둡니다.

| | 값 |
| --- | --- |
| 효과 | 히트 시 0ms |
| 리스크 | **조합 폭발** — `section×sort×region×category×keyword`라 히트율이 낮을 수 있음. 검색어가 들어가면 사실상 캐시 불가 |
| 판단 | 홈 3섹션처럼 **조합이 고정된 경로에만** 부분 적용. 탐색 전체에 적용은 부적합 |
| 주의 | 파이프라인 Stage 6 — **2·3(인덱스·쿼리 형태)을 건너뛰고 오면 느린 쿼리를 감추는 것** |

---

### 해결 6 — count 자체를 빠르게 (인덱스)

`type` 선택도 문제를 풀고 커버링 인덱스로 count가 인덱스만 읽게 만듭니다.

```sql
create index idx_ex_type_start on exhibitions(type, start_date);
create index idx_ex_type_end   on exhibitions(type, end_date);
create index idx_place_region   on exhibition_place(region);
create index idx_detail_price   on exhibition_detail(price);
```

| | 값 |
| --- | --- |
| 효과 | 상수배 개선. `section=free`·`region` 서브쿼리는 특히 클 것 |
| **한계** | **O(N)은 그대로.** 66만 행을 세는 사실은 변하지 않음 — 데이터가 늘면 다시 느려짐 |
| 판단 | 해결 1·2와 **함께** 해야 의미가 있음. 이것만으로는 곡선의 기울기가 안 바뀜 |

---

## 4. 권장 순서

| 순서 | 해결 | 왜 이 순서인가 | 되돌리기 |
| ---: | --- | --- | --- |
| **1** | 해결 1 (버리는 count 제거) | 응답 계약 변화 0, 커밋이 이미 존재. **가장 싸고 확실** | `git revert` |
| **2** | 프론트 `totalCount` 사용처 조사 | 해결 2/3/4 중 무엇이 가능한지가 여기서 갈림 | — |
| **3** | 해결 2 또는 3 또는 4 | 조사 결과에 따라. **여기가 진짜 해결** | 응답 필드 복구 |
| **4** | 해결 6 (인덱스) | 남은 slice·count 비용을 상수배 줄임 | `DROP INDEX` |
| — | 해결 5 (캐시) | 위를 다 하고도 부족할 때만 | — |

**각 단계 후 재측정** — 100만 볼륨에서 5분:
```bash
VUS=2 R1N=50 ./loadtest/run.sh 1000000 after-<라벨> 2
./loadtest/compare.sh
```

---

## 5. 측정으로 확인할 것

| 단계 | 확인 지표 | 기대 |
| --- | --- | --- |
| 해결 1 후 | 요청당 JDBC 문 수 | 5 → **4** |
| 해결 1 후 | S1(최신순) 100만 p50 | 8,890ms → **~7,100ms** (count 1회분 감소) |
| 해결 2 후 | S1 100만 p50 | → **~3,500ms** (filesort만 남음) |
| 해결 2 후 | S3(인기순) 100만 p50 | 5,382ms → **수십 ms** (배너처럼 평평해져야 정상) |
| 해결 6 후 | `EXPLAIN`의 `Extra` | `Using filesort` **사라짐** · `rows ≈ 21` |
| 전 단계 | 대조군 Z1 | 1~2ms 유지(안 그러면 그 런은 오염) |

> 🎯 **졸업 조건**: 목록 p95 ≤ 300ms를 **10만 볼륨에서** 통과. 현재는 10만에서 672ms로 실패 중입니다.
