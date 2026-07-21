# 전시 목록 수집(fetchAll)의 페이지네이션 — 왜 이렇게 생겼고, 어떻게 정리할 수 있나

작성일: 2026-07-21
대상 코드: `ingestion/infra/culture/CultureCatalogReader#fetchAll` (단건 호출은 `CultureExhibitionClient`)
관련: `CatalogListData` · `IngestionRun` · `V24__create_sync_run.sql` · `CatalogSynchronizer`

## 0. 이 문서가 답하는 것

`fetchAll()`에는 페이지 순회와 절단 판정이 얽혀 있다. 왜 그게 필요한지는 코드만 봐서는 읽히지 않는다 — 원천 API의 성질에서 나온 것이기 때문이다. 세 가지에 답한다.

1. 원천(공공데이터 API)이 어떤 식으로 동작하나
2. 그 동작에 우리 코드를 **어떻게 맞췄나** — 다섯 개의 결정
3. 핵심 흐름이 드러나게 정리한다면 어떤 모양인가

**적용 상태**: 전부 2026-07-21에 적용·확정됐다(2장 결정 5, 3장 3-2·3-5·3-6). **3-3과 3-3-1은 채택하지 않은 대안으로 남겨둔다.**

> ### ⚠️ 2026-07-21 후속 변경 — 절단 감지(`truncated`) 폐기
>
> **수집의 목표가 "전량 정합"에서 "신규 등록 포착"으로 좁혀졌다(사용자 결정).** "원천을 다 가져왔나"를 더는
> 묻지 않기로 했으므로, 그 물음에만 쓰이던 `truncated`를 코드·DB에서 제거했다(V42).
>
> - 삭제: `CatalogListData.truncated` · `IngestionRun.truncated` · `CultureCatalogReader#isTruncated` ·
>   `CatalogSynchronizer`의 절단 경고 로그 · `ingestion_run.truncated` 컬럼
> - 존치: `maxItems` 상한(폭주 방지) · `CatalogListData.totalCount`(원천 규모 추이)
>
> **아래 결정 2·결정 3과 "`seen`과 `collected`" 절은 폐기된 설계다** — 지웠을 때 "왜 있었고 왜 없앴나"를
> 잃으므로 이력으로 남긴다. 되살릴 일이 생기면(전량 정합으로 회귀) 여기서부터 읽으면 된다.
>
> **감수한 것**: 상한(`max-items: 500`)은 남아 있는데 상한에 걸린 사실을 아무도 보고하지 않는다. 원천이
> 500건을 넘기는 날 조용히 잘리고 우리는 알 수 없다 — V24가 애초에 풀려던 문제로 되돌아간 것이다.
> 신규 등록만 잡으면 된다는 전제가 깨지면 이 대가부터 다시 계산해야 한다.

---

## 1. 원천 API가 어떻게 동작하나

대상: 공공데이터포털 `한눈에보는문화정보`(data.go.kr 15138937)
엔드포인트: `GET {base}/realm2`(목록) · `GET {base}/detail2`(상세)
기본 base URL: `https://apis.data.go.kr/B553457/cultureinfo`

수집 코드의 모양을 결정한 원천의 성질은 다섯 가지다.

### 1-1. 오프셋 페이지네이션이다 (커서 없음)

목록은 `PageNo`(1부터) + `numOfrows`(페이지당 행 수)로 끊어 준다. "다음 페이지 토큰" 같은 건 없다. 그래서 **몇 번째 페이지까지 부를지를 호출자가 정해야 하고, 언제 멈출지도 호출자가 판단해야 한다.**

> 파라미터 이름 표기가 불규칙하다 — `PageNo`는 P가 대문자, `numOfrows`는 r이 소문자다. 한 글자만 틀려도 원천이 조용히 기본값으로 응답하기 때문에, HTTP Interface를 걷어내고 코드로 URI를 조립하게 된 뒤로는 `CultureExhibitionClientTest#요청선_경로와_쿼리파라미터`가 요청선 전체를 문자열로 못박아 두고 있다.

### 1-2. 총 건수를 알려준다 — 단, 응답 본문에

`<body><totalCount>N</totalCount>`. 응답을 한 번은 받아 봐야 전체 규모를 알 수 있다. 미리 알 수 없으니 "총 N건이니까 ⌈N/100⌉번 부르자" 같은 선계산이 불가능하다.

### 1-3. 마지막 페이지를 명시하지 않는다

`hasNext`나 `totalPages` 같은 필드가 없다. 마지막 페이지인지는 **받아 본 행 수로 추론**할 수밖에 없다 — 요청한 `numOfrows`보다 적게 왔으면 마지막이다.

### 1-4. HTTP 200을 주면서 실패한다

키 오류·한도 초과·제공기관 장애가 전부 **200 OK + 본문 `<header><resultCode>`** 로 온다. 정상은 `"00"` 하나뿐이다. 그래서 성공 판정을 HTTP 상태로 하면 안 되고, `CultureApiMapper#parse`가 `resultCode`를 보고 `CoreException(EXTERNAL_API_UNAVAILABLE)`을 던진다. 코드표는 `CultureResultCode`에 있다(data.go.kr 전 서비스 공통 표준이라 벤더 어휘 = infra 소유).

### 1-5. 응답이 XML이고, 인코딩 헤더가 부실하다

`Content-Type`에 charset이 빠지는 경우가 있어 Spring 기본 컨버터(ISO-8859-1)가 개입하면 한글이 깨진다. 그래서 `KoreaCultureInformationClientConfig`에서 UTF-8 `StringHttpMessageConverter`를 강제하고, 응답을 **문자열로 받아** `XmlMapper`로 직접 판다.

### 확인된 실측치

| 항목 | 값 | 출처 |
|---|---|---|
| 전시(D000) 총 건수 | 280건 | 2026-07-15 실측 (`V24__create_sync_run.sql`) |
| 목록 응답 태그 | 12필드 | 279건 전수 집계 (제공률 분석) |
| 상세 응답 태그 | +8필드 | 60건 전수 집계 |

### 아직 확인하지 않은 것

- 공식 호출 한도(일/초당). `CultureResultCode`에 한도초과 코드(`22`)는 있지만 실제 임계값은 미확인
- 상한(`max-items`)을 넘는 범위의 `PageNo`를 요청했을 때의 응답(빈 items인지 에러코드인지)
- 원천이 페이지 중간에 `numOfrows`보다 적게 주는 경우가 실재하는지

세 번째는 코드가 이미 안전하게 처리한다 — 2-4에서 설명한다.

---

## 2. 그래서 코드가 왜 이렇게 됐나 — 다섯 개의 결정

### 결정 1. 상한을 건다 (`maxItems`)

```yaml
app:
  ingestion:
    exhibition-catalog:
      realm: EXHIBITION
      page-size: 100
      max-items: 500
```

1-1 때문에 종료 조건을 우리가 쥐어야 하는데, 원천이 이상 동작하거나 우리 종료 판정이 틀리면 루프가 무한정 돈다. 그래서 **하드 상한 500건**을 건다. 어댑터는 이걸 호출 횟수로 옮긴다 — `maxCalls() = ceil(500 / 100) = 5`.

현재 원천이 280건이니 여유가 있다. **문제는 원천이 500을 넘기는 날이다.**

> **상한을 페이지가 아니라 건수로 표현하는 이유**(2026-07-21 변경): "5페이지"는 페이지 크기를 알아야 의미가 생기는 전송 단위라 도메인이 말할 수 있는 문장이 아니다. "한 실행에 최대 500건"은 그 자체로 정책이다. 몇 번 나눠 부를지는 어댑터가 유도한다.

### 결정 2. 상한에 걸린 걸 감지한다 (`truncated`) — ❌ 폐기(2026-07-21)

> **폐기됨.** 수집 목표가 신규 등록 포착으로 좁혀져 제거했다(V42). 아래는 당시 판단의 이력이다.

이게 이 메서드에서 제일 중요한 부분이고, 페이지네이션 코드가 단순해지지 못하는 이유다.

상한을 걸면 필연적으로 **조용한 절단**이 생긴다. 원천에 600건이 있어도 500건만 가져오고, 목록 호출 5번은 각자 200 OK로 멀쩡히 성공한다. 개별 호출의 성패로는 이 사건을 표현할 자리가 없다 — 그 호출들은 전부 SUCCESS다.

그래서 절단은 **배치 단위 사실**로 따로 기록한다. `CatalogListData.truncated` → `IngestionRun` → `sync_run` 테이블. `CatalogSynchronizer`가 이 플래그를 보고 `log.warn`을 찍는다.

> 이것 때문에 포트가 `List<CatalogExhibitionData>`가 아니라 `CatalogListData` record를 반환한다. 원천이 말한 총 건수는 **응답에만** 있고(어댑터 안에서 파싱되고 버려졌었다), 절단 여부는 **페이지를 순회한 어댑터만** 안다. 목록만 돌려주면 호출부가 `sync_run`의 컬럼을 채울 수 없다.

### 결정 3. 절단 판정에 증거의 우선순위를 둔다 — ❌ 폐기(2026-07-21)

> **폐기됨.** `isTruncated`가 통째로 삭제됐다. 판정 순서의 논리 자체는 되살릴 때 다시 쓸 값이라 남긴다.

```java
private boolean isTruncated(List<CultureApiResponse> pages, CatalogFetchCriteria criteria,
        Integer totalCount, int seen) {
    if (totalCount != null) {
        return seen < totalCount;
    }
    return !pages.isEmpty() && pages.get(pages.size() - 1).items().size() >= criteria.pageSize();
}
```

증거가 두 종류다.

- **직접 증거** — 원천이 "280건 있다"고 했는데 우리가 250건만 봤다 → 절단
- **간접 증거** — 상한까지 다 돌았는데 마지막 페이지가 꽉 차 있었다 → 아마 더 있다

직접 증거가 있으면 그걸 쓴다. 간접 증거를 먼저 쓰면 **상한과 원천 크기가 정확히 같을 때 오판**한다 — 500건 상한에 원천이 정확히 500건이면, 5페이지를 꽉 채워 돌고 끝나므로 간접 증거는 "절단"이라고 말한다. 실제로는 다 가져왔는데도. 그래서 판정 순서가 규칙의 일부다.

### 결정 4. 마지막 페이지는 행 수로 추론한다

```java
if (page.items().size() < criteria.pageSize()) {
    break; // 마지막 페이지
}
```

1-3 때문에 이 방법밖에 없다. 부작용은 **원천이 페이지 중간에 요청보다 적게 주면 조기 종료**한다는 것이다.

> ⚠️ **2026-07-21 이후 이 부작용에 안전망이 없다.** 원래는 결정 3의 직접 증거(`seen < totalCount`)가 그 조기
> 종료를 절단으로 잡아내서 "두 판정이 서로를 보완"했는데, 결정 3이 폐기되면서 보완 쪽이 사라졌다. 이제
> 원천이 페이지 중간에 적게 주면 **조용히 거기서 끊기고 아무도 모른다.** 신규 등록만 잡으면 된다는 전제 위에서
> 감수한 대가다.

### 결정 5. 요청 조건은 인프라가 아니라 호출자가 정한다 (2026-07-21 변경)

원래 `realmCode`·`numOfRows`·`maxPages`가 전부 `PublicDataProperties`(어댑터 설정)에 있었다. 인프라는 외부와 맞닿아 **대신 가져다주는** 계층이지 수집 범위를 정하는 계층이 아니라서, 요청 변수를 포트 인자로 올렸다.

| | 값 | 소유 | 근거 |
|---|---|---|---|
| 접속 설정 | `base-url` · `service-key` · `timeout-seconds` | infra (`PublicDataProperties`) | 어떻게 닿느냐 — 도메인이 의견을 가질 수 없다 |
| 요청 조건 | `realm` · `page-size` · `max-items` | 수집 도메인 (`CatalogFetchCriteria`) | 무엇을 얼마나 — 시스템에 뭐가 들어오는지를 결정한다 |

```java
CatalogListData fetchAll(CatalogFetchCriteria criteria);
```

상세 조회(`fetchDetailSnapshot(externalId)`)는 처음부터 요청 변수를 호출자에게서 받고 있었다 — **목록 경로만 예외**였고, 이 변경으로 둘이 같은 규칙을 따른다.

`realm`은 벤더 코드가 아니라 도메인 개념(`ExhibitionRealm.EXHIBITION`)으로 넘긴다. `"D000"`은 문화포털의 방언이라 도메인이 들고 있으면 **다른 원천 어댑터가 생겼을 때 그쪽도 문화포털 코드표를 알아야 한다.** 개념 → 코드 매핑은 요청선을 조립하는 `CultureExhibitionClient#realmCodeOf`에만 있다.

### 놓치기 쉬운 것: `seen`과 `collected`는 다른 수다 — ❌ 폐기(2026-07-21)

> **폐기됨.** `seen`은 절단 판정의 유일한 소비자였으므로 `isTruncated`와 함께 삭제됐다. 지금 코드에는
> `collected` 하나뿐이라 "합치면 안 되는 두 수"라는 지뢰 자체가 없다. 절단 판정을 되살린다면 이 절이 다시 유효해진다.

```java
List<CatalogExhibitionData> collected = pages.stream()
        .flatMap(page -> page.items().stream())
        .map(mapper::toCatalog)
        .filter(CatalogExhibitionData::isPersistable)   // externalId·title 있는 것만
        .toList();                                      // 우리가 keep한 것
int seen = pages.stream().mapToInt(page -> page.items().size()).sum();  // 원천이 준 행 수(필터 이전)
```

절단 판정은 **원천이 준 수**와 비교해야 한다. `collected.size()`로 바꾸면 필터에서 걸러진 불량 행이 전부 "절단"으로 오인된다. 리팩터링할 때 "카운터 두 개는 중복이니 합치자"가 제일 밟기 쉬운 지뢰다.

---

## 3. 클린코드 — 핵심 흐름이 보이게 하려면

### 3-1. 무엇이 읽기를 방해했나 (변경 전 진단)

변경 전 `fetchAll`은 네 가지를 한 몸에서 했다.

| 관심사 | 코드 |
|---|---|
| 가드 | `isConfigured()` 체크 |
| 순회 제어 | for + break |
| 누적 | `collected` `totalCount` `seen` `exhaustedPages` 네 개의 가변 지역변수 |
| 판정·조립 | truncated 삼항 + `new CatalogListData(...)` |

읽는 사람이 "페이지를 돌면서 모은다"는 **한 줄짜리 골격**을 보려면, 그 사이에 흩어진 가변 상태 네 개를 머릿속에서 동시에 추적해야 한다. 문제는 for문이 아니라 **누적 상태가 메서드 본문에 노출되어 있다는 것**이다.

그리고 이 상태들이 지역변수라서, 절단 판정 규칙(결정 3 — 이 파일에서 제일 미묘한 로직)을 **HTTP 없이는 테스트할 수 없다.** MockWebServer를 띄우고 페이지 시나리오를 꾸며야만 검증된다.

### 3-2. 적용: 순회와 접기를 가르고, 단건 호출과도 분리한다 (2026-07-21, 적용됨)

문제의 뿌리는 **"페이지를 도는 일"과 "결과로 접는 일"이 한 몸**이라는 데 있었다. 둘을 가르면 가변 누적자 네 개가 전부 사라진다.

```java
@Override
public CatalogListData fetchAll(CatalogFetchCriteria criteria) {
    if (!client.isConfigured()) {
        log.info("CULTURE_API_KEY 미설정 — 동기화 스킵");
        return CatalogListData.none();
    }
    return toListData(fetchPages(criteria), criteria);
}
```

- `fetchPages(criteria)` — 상한까지 순회하고 덜 찬 페이지에서 멈춘다. **어디까지 부를지만** 판단하고 내용은 안 본다.
- `toListData(pages, criteria)` — 적재 가능 필터·총 건수·절단 판정. **받아 온 것을 접기만** 한다.
- `totalCountOf(pages)` / `isTruncated(...)` — 접기 안의 두 규칙을 각자 이름 붙여 떼어냈다.

이어서 이 넷을 **별도 클래스로 분리**했다(216줄 / 메서드 9개짜리 한 클래스가 목록 페이징·상세·감사·벤더 매핑을 다 들고 있었다).

| 클래스 | 하는 일 | 바뀌는 이유 |
|---|---|---|
| `CultureCatalogReader` **(포트 구현)** | 페이징 + 접기 | 원천의 **페이징 방식**이 바뀔 때 |
| `CultureExhibitionClient` | 한 페이지·한 상세 호출 + 감사 + 예외 변환 | **요청선·감사**가 바뀔 때 |

**포트 구현이 순회 쪽에 있어야 하는 이유**: `fetchAll`은 `CatalogListData`(items + totalCount + `truncated`)를 돌려주기로 약속돼 있는데, `truncated`는 여러 페이지를 다 봐야 나오는 결론이다. 단건 호출자가 포트를 구현하면 이 약속을 지킬 수 없다.

접속 설정은 `CultureExhibitionClient` 밖으로 새지 않는다 — Reader는 `PublicDataProperties`를 읽지 않고 `client.isConfigured()`로 "쓸 수 있나"만 묻는다.

**플래그가 사라진 게 가장 큰 소득이다.** 종전 `exhaustedPages`는 `true`로 시작해 break에서 뒤집히는 이중부정이라, 초기값의 의미를 알려면 루프 끝까지 읽어야 했다. 페이지 목록을 그대로 들고 있으면 그 사실은 **데이터에서 복원된다.**

```java
// 마지막 페이지를 봤다 = 마지막으로 받은 페이지가 덜 찼다. 종료 사유를 플래그로 나를 필요가 없다.
return !pages.isEmpty() && pages.get(pages.size() - 1).items().size() >= criteria.pageSize();
```

그리고 이 문서가 "제일 밟기 쉬운 지뢰"라고 적어둔 `seen` vs `collected`가, 이제 `toListData` 안에서 **두 줄 간격으로 나란히** 보인다. 루프 여기저기 흩어져 있을 때보다 차이가 눈에 띈다.

주의점 하나: 페이지 응답을 전부 메모리에 들고 접는다. 현재 상한 500건(5페이지)에서는 무의미한 비용이지만, **`max-items`를 크게 키운다면 스트리밍 누적으로 되돌려야 한다**(그때가 3-3의 누적자 객체가 필요해지는 시점이다).

이 변경으로 절단 판정 4경우(직접 증거·간접 증거·상한==원천크기 경계·필터 이전 기준)에 테스트가 붙었다 — 종전에는 어느 것도 직접 검증되지 않았다.

### 3-3. 대안(미적용): 누적 상태를 객체로 뽑는다

순회 중에 쌓이는 것들을 `CatalogPageScan` 하나로 묶는 방법도 있다. 이름 그대로 "목록 순회 1회분"이다. **3-2를 택해 적용하지 않았지만**, 페이지를 전부 들고 있을 수 없을 만큼 상한이 커지면 이쪽이 답이 된다.

```java
/**
 * 목록 순회 1회의 누적 상태 — 페이지를 흡수하며 "원천을 다 가져왔나"를 스스로 판정한다.
 * 절단 판정 규칙(직접 증거 우선)이 여기 갇히므로 HTTP 없이 단위 테스트할 수 있다.
 */
final class CatalogPageScan {

    private final int pageSize;
    private final CultureApiMapper mapper;

    private final List<CatalogExhibitionData> collected = new ArrayList<>();
    private Integer totalCount;   // 원천이 말한 총 건수(첫 응답에서 고정)
    private int seen;             // 원천이 준 행 수 — 필터 이전이라 collected.size()와 다르다
    private boolean sawLastPage;  // 덜 찬 페이지를 실제로 만났나

    CatalogPageScan(int pageSize, CultureApiMapper mapper) {
        this.pageSize = pageSize;
        this.mapper = mapper;
    }

    /** 한 페이지를 흡수한다. @return 더 부를 페이지가 남았나(false = 마지막 페이지였다) */
    boolean absorb(CultureApiResponse page) {
        if (totalCount == null && page.body() != null) {
            totalCount = page.body().totalCount();
        }
        List<CultureApiResponse.Item> items = page.items();
        seen += items.size();
        items.stream().map(mapper::toCatalog)
                .filter(CatalogExhibitionData::isPersistable)
                .forEach(collected::add);
        if (items.size() < pageSize) {
            sawLastPage = true;
            return false;
        }
        return true;
    }

    CatalogListData toListData() {
        // 원천이 총 건수를 알려줬으면 그 말과 우리가 본 수를 비교하는 게 가장 직접적인 증거다.
        // 모를 때만 "마지막 페이지를 못 만난 채 끝났다"는 간접 증거로 판정한다 — 순서가 규칙의 일부다.
        boolean truncated = totalCount != null ? seen < totalCount : !sawLastPage;
        return new CatalogListData(List.copyOf(collected), totalCount, truncated);
    }
}
```

그러면 `fetchAll`은 골격만 남는다.

```java
@Override
public CatalogListData fetchAll(CatalogFetchCriteria criteria) {
    if (!properties.isConfigured()) {
        // 인증키 미설정: 외부 호출을 시도하지 않고 스킵한다(데모는 시드 데이터로 동작).
        log.info("CULTURE_API_KEY 미설정 — 동기화 스킵");
        return CatalogListData.none();
    }
    CatalogPageScan scan = new CatalogPageScan(criteria.pageSize(), mapper);
    for (int pageNo = 1; pageNo <= criteria.maxCalls(); pageNo++) {
        if (!scan.absorb(fetchListPage(criteria, pageNo))) {
            break; // 마지막 페이지
        }
    }
    return scan.toListData();
}
```

얻는 것:

- **골격이 4줄로 읽힌다** — "상한까지 페이지를 돌면서 흡수하고, 마지막 페이지면 멈춘다"
- **가변 상태가 메서드에서 사라진다** — 네 개가 객체 안으로 들어가고, `fetchAll`의 지역변수는 `scan`과 루프 변수뿐
- **절단 규칙을 HTTP 없이 테스트한다** — `absorb`를 원하는 순서로 먹여서 `toListData().truncated()`를 검증. "상한 = 원천 크기" 같은 경계(결정 3)를 MockWebServer 없이 직접 찌를 수 있다
- **`exhaustedPages`의 이중부정이 사라진다** — 종전에는 `true`로 시작해 break에서 `false`로 뒤집었는데, `sawLastPage`는 실제로 본 사실을 그대로 기록한다

`sawLastPage`가 스스로 `exhaustedPages`를 대신할 수 있는 이유: 루프가 상한까지 다 돌았다는 건 **덜 찬 페이지를 한 번도 못 만났다**는 뜻과 같다. 그래서 스캔 객체가 루프의 종료 사유를 따로 전달받을 필요가 없다.

### 3-3-1. 더 가벼운 대안: 판정만 도메인으로

객체 하나 추가가 과하다면, 절단 규칙만 `CatalogListData`의 정적 팩토리로 올린다.

```java
public static CatalogListData of(List<CatalogExhibitionData> items,
                                 Integer totalCount, int seen, boolean sawLastPage) {
    return new CatalogListData(items, totalCount,
            totalCount != null ? seen < totalCount : !sawLastPage);
}
```

`fetchAll`의 가변 상태는 그대로 남지만, **제일 미묘한 규칙이 도메인 record로 옮겨가 단위 테스트가 붙는다.** 투입 대비 효과로는 이쪽이 가장 싸다.

### 3-4. 함정: 스트림으로 바꾸고 싶어지는데, 안 된다

```java
// 이렇게 하고 싶지만 틀린다
IntStream.rangeClosed(1, criteria.maxCalls())
        .mapToObj(this::fetchListPage)
        .takeWhile(page -> page.items().size() == criteria.pageSize())
        ...
```

두 가지가 깨진다.

1. **`takeWhile`은 조건을 깬 원소를 버린다.** 마지막(덜 찬) 페이지가 통째로 사라진다. Java에는 "이것까지 포함하고 멈춰라"가 없다.
2. **`IntStream`은 게으르지만 그걸 신뢰하기 어렵다.** `mapToObj`가 HTTP 호출이라 평가 시점이 곧 부수효과 발생 시점인데, 스트림 파이프라인은 그 시점을 읽는 사람에게 감춘다. 500건 상한을 지키는 코드에서 "몇 번 호출되는가"가 안 보이는 건 손해다.

부수효과가 있고 종료 조건이 마지막 원소에 걸린 순회는 **명시적 for + break가 정직하다.** 이 for문은 리팩터링 대상이 아니다.

### 3-5. 이미 적용한 것 — "감춤"을 걷어낸 판단들 (2026-07-21, 적용됨)

3-2·3-3은 제안이지만, 아래는 실제로 반영됐다. 관통하는 기준은 하나다.

> **정보를 감추는 헬퍼는 걷어내고, 불변식을 강제하는 헬퍼는 남긴다.**

| 대상 | 처리 | 왜 |
|---|---|---|
| HTTP Interface `CultureApi` | **제거** — `RestClient` 직접 호출 | 선언형 프록시가 요청선을 애노테이션으로 흩어놔, 어댑터만 봐선 어떤 URL이 나가는지 안 보였다 |
| `request(path, UnaryOperator<UriBuilder>)` | **인라인** | URI 조립을 감췄다. 지금은 각 메서드에서 경로·파라미터가 그대로 읽힌다 |
| `transportFailure(path, e)` | **인라인** | 나가는 예외를 감췄다 |
| `truncated(totalCount, seen, exhaustedPages)` | **인라인** → 이후 3-2에서 `isTruncated(...)`로 재추출 | 인라인 시점엔 호출부 1곳짜리 삼항이라 이름값이 없었다. 3-2로 순회·접기를 가르면서 접기 쪽 규칙으로 자리가 생겨 다시 떼어냈다 |
| `record(ExternalApiCallLog)` | **유지** | ← 성격이 다르다 |

`record`만 남긴 이유가 이 기준을 잘 보여준다. 이 메서드는 정보를 감추는 게 아니라 **"감사 저장이 실패해도 수집을 깨뜨리지 않는다"는 불변식을 강제**한다. 호출부가 5곳이고 그중 둘은 이미 `catch` 안이라, 인라인하면 catch 속 try/catch가 5벌 생긴다. 한 곳만 가드를 빠뜨려도 **감사 저장 실패가 원래 API 실패 예외를 덮어써서** 진짜 원인이 사라진다 — 컴파일도 테스트도 통과하고, 운영에서 DB가 흔들릴 때만 드러난다.

> **남은 중복 하나**: 예외 변환의 `if (e instanceof CoreException) throw ...` 가드가 `fetchListPage`·`fetchDetailSnapshot` 두 곳에 복사돼 있다. 이걸 빠뜨리면 `CultureApiMapper`의 "비정상 코드(한도초과 vs 키오류)" 메시지가 일반 문구로 덮인다. **세 번째 엔드포인트를 추가할 때 이 가드도 같이 복사해야 한다.**

부수 효과로 요청선 테스트(`CultureExhibitionClientTest#요청선_경로와_쿼리파라미터`)가 생겼다. 경로·파라미터명이 애노테이션에서 코드로 옮겨오면서, 그것들을 못박는 테스트가 없으면 `PageNo`/`numOfrows` 오타를 아무도 못 잡는 상태가 됐기 때문이다.

### 3-6. 만들지 않기로 한 것 — 검증 없는 통과층

`ingestion/domain/KoreaCultureExhibitionService`를 두어 포트 호출을 감싸는 안이 있었으나 **폐기했다.** 계층을 하나 더 두는 것은 그 자체로 클린코드가 아니다.

```java
// 폐기된 형태
public CatalogListData fetchAll(CatalogFetchCriteria criteria) {
    return exhibitionCatalogClient.fetchAll(criteria);   // 그냥 넘긴다
}
```

세 가지가 걸렸다.

1. **위치** — `domain/`에 `@Service`. `Domain은 Spring/JPA/HTTP 모름`(CLAUDE.md)에 어긋나고, 이 프로젝트엔 `domain/` 아래 Service 선례가 하나도 없다. 조율은 전부 application에 있다.
2. **이름** — `KoreaCulture…`. `"D000"`을 어댑터로 밀어넣은 작업(결정 5)을 클래스 이름이 되돌린다. 포트는 `ExhibitionCatalogClient`(벤더 없음), 구현만 `CultureExhibitionClient`(벤더 있음)가 이 코드의 규칙이다.
3. **가치** — 검증이 없으면 hop만 하나 는다.

넣으려던 검증이 실은 이미 다른 자리에 있었다는 점도 확인됐다.

| 검증 | 이미 있는 자리 |
|---|---|
| 요청값(분야·페이지 크기·상한) | `CatalogFetchCriteria` 컴팩트 생성자 |
| 적재 가능 여부(externalId·title) | 어댑터의 `isPersistable` 필터 |
| 응답 성공 여부(resultCode) | `CultureApiMapper#parse` |

아직 아무 데도 없는 것은 **절단(`truncated`) 대응 정책**뿐이다(지금은 `CatalogSynchronizer`가 로그만 찍는다). 여기에 "절단이면 실행 중단" 같은 규칙이 생기는 날, 그때는 새 협력자를 둘 값어치가 있다 — 그때도 자리는 `ingestion/application`이고 이름에 벤더는 들어가지 않는다.

---

## 4. 건드리면 안 되는 것 (회귀 체크리스트)

리팩터링하든 안 하든, 다음이 깨지면 조용히 데이터가 샌다.

| 지켜야 할 것 | 깨지면 |
|---|---|
| ~~`seen`은 필터 **이전** 행 수~~ | 폐기(2026-07-21) — 절단 판정과 함께 삭제 |
| ~~절단 판정은 **직접 증거 우선**~~ | 폐기(2026-07-21) — `isTruncated` 삭제 |
| `totalCount`는 **첫 응답 값 고정** | 페이지마다 덮으면 중간 응답의 결측에 흔들림 |
| `null` totalCount는 **"모른다"** (0 아님) | 호출 자체가 없었던 것과 원천 0건이 구별 불가 |
| 마지막 페이지 판정은 `< criteria.pageSize()` | `==`으로 바꾸면 덜 찬 페이지를 못 잡음 |
| 요청 파라미터 표기 `PageNo`/`numOfrows` | 원천이 기본값으로 조용히 응답 |

마지막 항목은 `CultureExhibitionClientTest#요청선_경로와_쿼리파라미터`가 잡는다. 나머지는 **현재 MockWebServer 시나리오로만 간접 검증**되고 있고, 3-2나 3-3을 적용하면 직접 단위 테스트를 붙일 수 있다. 그게 이 리팩터링의 실질적인 이득이다.
