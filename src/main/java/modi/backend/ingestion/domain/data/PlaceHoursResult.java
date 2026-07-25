package modi.backend.ingestion.domain.data;

import modi.backend.domain.exhibition.hours.PlaceHoursData;

/**
 * 영업시간 조회 결과 — 파싱된 도메인 값({@link PlaceHoursData})과 벤더 스냅샷 원문을 함께 노출한다(ADR-13).
 * <p>
 * <b>도메인이 선언하고 벤더 응답(infra)이 구현한다(DIP 역전)</b> — 예전의 {@code PlaceHoursFetch}(값 묶음)와
 * {@code GooglePlaceVendorItem}(원문 묶음) 두 record를 대체한다. 구글 응답 record({@code GooglePlaceDto.Place})가
 * 자기를 이 결과로 표현하고, mock은 작은 구현이 대신한다({@code CultureDetail2Response.Item ↔ CultureDetailPayload}과 같은 패턴).
 * <p>
 * 스냅샷 필드({@link #placeId()}·{@link #displayNameText()}·{@link #formattedAddress()}·{@link #regularOpeningHoursJson()})는
 * 벤더 원본 적재용이라 <b>mock은 전부 null</b>이다(정준층에 provider=MOCK으로만 남고 벤더층은 비어 있는 게 정상).
 */
public interface PlaceHoursResult {

	/** 파싱된 요일별 영업시간(정준층 표시값의 재료). 장소는 찾았으나 영업시간이 없으면 빈 값이다. */
	PlaceHoursData data();

	/** 구글 Place 리소스 id — 재조회·중복 판정의 벤더 측 키(mock=null). */
	String placeId();

	/** 표시 장소명(mock=null). 응답 record의 {@code displayName}(DisplayName 타입) 접근자와 겹치지 않게 Text 접미. */
	String displayNameText();

	/** 벤더가 준 정규화 주소(mock=null). */
	String formattedAddress();

	/** regularOpeningHours(periods·weekdayDescriptions) 구조 보존 JSON — 스냅샷 컬럼 적재용(mock=null). */
	String regularOpeningHoursJson();
}
