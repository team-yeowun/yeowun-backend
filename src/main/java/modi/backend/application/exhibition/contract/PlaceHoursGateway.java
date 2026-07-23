package modi.backend.application.exhibition.contract;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import modi.backend.domain.exhibition.hours.PlaceHoursStatus;
import modi.backend.domain.exhibition.hours.PlaceHoursVendor;

/**
 * 전시장 영업시간 정준층({@code place_hours}) 계약 — 수집(ingestion)이 조회 대상 선별·결과 반영·재검증 가드에
 * 쓰는 코어의 좁은 포트(ADR-12). 벤더 원본 적재는 수집 쪽 소관이라 여기 없다.
 *
 * <p>구 PlaceHoursBackfill — 레거시 뒤채움 계약(ExhibitionBackfill, 삭제됨)과 무관한 정준 통로라 Gateway로 개명.
 */
public interface PlaceHoursGateway {

	/** HOURS 작업의 target_key(= 전시장 자연키)로 조회 대상을 만든다. 전시장이 없거나 주소가 없으면 빈 Optional. */
	Optional<PlaceHoursTarget> resolvePlaceHoursTarget(String placeKey);

	/** 영업시간 조회 대상을 전시장 단위로 최대 {@code maxVenues}개 — 주소가 있고 정준행이 없거나 stale한 전시장. */
	List<PlaceHoursTarget> findPlacesNeedingHours(LocalDateTime staleBefore, int maxVenues);

	/** 한 전시장의 조회 결과를 정준층에 반영한다(값이 없으면 formatted=null로 시각만 남겨 재조회 백오프). */
	void applyHours(Long exhibitionPlaceId, String formatted, PlaceHoursStatus status, PlaceHoursVendor vendor,
			LocalDateTime now);

	/** 조회 전송 실패를 정준층에 남긴다(재시도 대상 유지). 기록 실패는 삼킨다 — 보강을 깨지 않는다. */
	void markHoursFailure(Long exhibitionPlaceId, PlaceHoursVendor vendor);

	/**
	 * 재검증 가드 판정용 — 전시장·정준행이 모두 있으면 그 동기화 상태를 돌려준다.
	 * 빈 Optional = 전시장 없음 또는 최초 조회 전(재검증 이벤트 대상 아님).
	 *
	 * @param syncedAt 마지막 성공 확인 시각(null이면 모름 — 재검증 허용)
	 */
	Optional<HoursSyncState> findHoursSyncState(String placeKey);

	record HoursSyncState(LocalDateTime syncedAt) {
	}
}
