package modi.backend.application.exhibition.contract;

import java.time.LocalDateTime;

import modi.backend.domain.exhibition.hours.PlaceHoursStatus;
import modi.backend.domain.exhibition.hours.PlaceHoursVendor;

/**
 * 전시장 영업시간 정준층({@code place_hours}) 계약 — 수집(ingestion)이 조회 결과를 반영하는 코어의 좁은
 * 포트(ADR-12). 벤더 원본 적재는 수집 쪽 소관이라 여기 없다.
 *
 * <p><b>반영 하나만 남았다(설계 D4)</b>: 영업시간 재검증이 폐기되면서 대상 선별({@code findPlacesNeedingHours})·
 * 재검증 가드({@code findHoursSyncState})·대상 해소({@code resolvePlaceHoursTarget})·실패 마킹이 전부 소멸했다.
 * 조회는 전시장 최초 초기화(PLACE_STAGED 소비) 1회뿐이고, 재시도 상태는 아웃박스가 소유한다.
 */
public interface PlaceHoursGateway {

	/** 한 전시장의 조회 결과를 정준층에 반영한다(값이 없으면 formatted=null로 시각만 남긴다 — NO_DATA도 해소). */
	void applyHours(Long exhibitionPlaceId, String formatted, PlaceHoursStatus status, PlaceHoursVendor vendor,
			LocalDateTime now);
}
