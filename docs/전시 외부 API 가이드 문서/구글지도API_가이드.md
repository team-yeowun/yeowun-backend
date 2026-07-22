# 구글 지도 Places API 가이드

## 1. 요청

| 항목 | 값 |
|---|---|
| Base URL | `https://places.googleapis.com` |
| Endpoint | `POST /v1/places:searchText` |
| 인증 | `X-Goog-Api-Key` 헤더 (39자, URL 노출 방지를 위해 쿼리스트링이 아닌 헤더로 전달) |
| 필드 제한 | `X-Goog-FieldMask` 헤더 (New API 필수 — 없으면 400) |

**요청 본문 예시**
```json
{
  "textQuery": "부산현대미술관 부산 사하구 낙동남로 1191",
  "languageCode": "ko",
  "regionCode": "KR"
}
```

**FieldMask**
```
places.id,places.displayName,places.formattedAddress,places.regularOpeningHours
```

`places.regularOpeningHours` 하나로 지정하면 그 하위 객체(`openNow`, `periods`, `weekdayDescriptions`, `nextCloseTime`)가 전부 딸려온다. 필드 단위 세부 지정은 지원하지 않는다.

---

## 2. 응답 원문

```json
{
  "places": [
    {
      "id": "ChIJjWpvtAPDaDURcEIlgF6Spnw",
      "formattedAddress": "부산광역시 사하구 낙동남로 1191",
      "regularOpeningHours": {
        "openNow": true,
        "periods": [
          { "open": { "day": 0, "hour": 10, "minute": 0 }, "close": { "day": 0, "hour": 18, "minute": 0 } },
          { "open": { "day": 2, "hour": 10, "minute": 0 }, "close": { "day": 2, "hour": 18, "minute": 0 } },
          { "open": { "day": 3, "hour": 10, "minute": 0 }, "close": { "day": 3, "hour": 18, "minute": 0 } },
          { "open": { "day": 4, "hour": 10, "minute": 0 }, "close": { "day": 4, "hour": 18, "minute": 0 } },
          { "open": { "day": 5, "hour": 10, "minute": 0 }, "close": { "day": 5, "hour": 18, "minute": 0 } },
          { "open": { "day": 6, "hour": 10, "minute": 0 }, "close": { "day": 6, "hour": 18, "minute": 0 } }
        ],
        "weekdayDescriptions": [
          "월요일: 휴무일",
          "화요일: 오전 10:00 ~ 오후 6:00",
          "수요일: 오전 10:00 ~ 오후 6:00",
          "목요일: 오전 10:00 ~ 오후 6:00",
          "금요일: 오전 10:00 ~ 오후 6:00",
          "토요일: 오전 10:00 ~ 오후 6:00",
          "일요일: 오전 10:00 ~ 오후 6:00"
        ],
        "nextCloseTime": "2026-07-22T09:00:00Z"
      },
      "displayName": { "text": "부산현대미술관", "languageCode": "ko" }
    }
  ]
}
```

---

## 3. 응답 필드 해설

| 필드 | 타입 | 비고 |
|---|---|---|
| `places[].id` | string | 구글 장소 고유 ID |
| `places[].formattedAddress` | string | 포맷된 주소 |
| `places[].displayName.text` | string | 장소명 |
| `places[].regularOpeningHours.periods[]` | array | 요일별 영업 구간. **휴무일은 아예 원소가 없음** |
| `places[].regularOpeningHours.weekdayDescriptions[]` | array | 사람이 읽는 요일별 문구, **항상 월→일 고정 순서** |
| `places[].regularOpeningHours.openNow` | boolean | 요청 시점 영업 여부 (실시간성 값) |
| `places[].regularOpeningHours.nextCloseTime` | string(UTC) | 다음 마감 시각 (실시간성 값) |

### day 인덱스 매핑 (구글 규격)

| day | 0 | 1 | 2 | 3 | 4 | 5 | 6 |
|---|---|---|---|---|---|---|---|
| 요일 | 일 | 월 | 화 | 수 | 목 | 금 | 토 |

> `weekdayDescriptions`는 월요일부터 시작하는 반면, `periods[].open.day`는 일요일이 0인 구글 API 공통 규격을 따른다. **두 필드가 서로 다른 요일 기준을 쓰므로 혼용하지 않는다.**

---

## 4. 예시 데이터로 보는 파싱 결과

이 응답을 `GoogleMapsDto`로 파싱하면 `periods`만 도메인 값으로 변환된다.

| 요일 | 영업시간 |
|---|---|
| 일 | 10:00 ~ 18:00 |
| 월 | *(periods에 원소 없음 → 휴무)* |
| 화 | 10:00 ~ 18:00 |
| 수 | 10:00 ~ 18:00 |
| 목 | 10:00 ~ 18:00 |
| 금 | 10:00 ~ 18:00 |
| 토 | 10:00 ~ 18:00 |

---

## 5. 주의사항 / 엣지케이스

- **휴무일 표현 방식**: 구글은 휴무일을 별도 플래그가 아니라 `periods`에서 해당 요일을 통째로 생략하는 방식으로 표현한다. 따라서 도메인 로직은 "그 요일이 `WeeklyOpeningHours`에 없으면 휴무"로 해석해야 하며, 별도의 결측 처리 없이 이 값을 그대로 신뢰하면 안 된다.
- **`openNow` / `nextCloseTime`은 도메인 값에 반영되지 않음**: 두 필드 모두 요청 시점 기준의 실시간 값이라, 저장 즉시 stale해진다. 스냅샷 JSON(`regular_opening_hours` 컬럼)에는 원본 그대로 남지만, `WeeklyOpeningHours`로의 변환 대상에서는 제외된다. 캐싱 전략(TTL, staleness tolerance) 설계 시 이 필드들은 애초에 캐시할 가치가 없는 값이라는 점을 감안한다.
- **24시간 영업 등 `close`가 없는 구간**: 현재 파싱 로직(`TimePoint.usable()`)은 `open`/`close` 양쪽이 모두 유효해야 반영하므로, close 없이 open만 있는 구간은 조용히 스킵된다. P1 정교화 대상으로 남아 있는 엣지케이스.
