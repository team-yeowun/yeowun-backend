package modi.backend.domain.exhibition.hours;

import modi.backend.domain.exhibition.hours.WeeklyOpeningHours;

/**
 * 한 장소의 영업시간 조회 결과(파싱 후 도메인 값). 인프라(구글/mock)와 애플리케이션 사이 경계 DTO —
 * HTTP·JSON 세부를 도메인에 노출하지 않는다. 벤더 원문은 수집(ingestion) 쪽 {@code PlaceHoursResult}의
 * 스냅샷 필드가 따로 나른다(ADR-13) — 코어는 원문을 모른다.
 *
 * @param weeklyHours 요일별 영업시간(정보 없으면 {@link WeeklyOpeningHours#hasNoOpenDay()}). 정준층 표시값의 재료다.
 */
public record PlaceHoursData(WeeklyOpeningHours weeklyHours) {
}
