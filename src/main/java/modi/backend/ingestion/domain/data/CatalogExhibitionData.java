package modi.backend.ingestion.domain.data;

import modi.backend.domain.exhibition.catalog.ExhibitionCategory;
import modi.backend.domain.exhibition.catalog.ExhibitionRegion;

import java.time.LocalDate;

/**
 * 외부 전시 API 한 건의 정규화된 수집 데이터(도메인 포트 출력).
 * 인프라(외부 응답 파싱)와 애플리케이션(Exhibition 매핑) 사이의 경계 DTO — HTTP·XML/JSON 세부를 도메인에 노출하지 않는다.
 * 결측이 잦은 원천 특성상 대부분 필드가 nullable이다(공공데이터 리뷰: 좌표·썸네일·가격 빈 값 빈번).
 *
 * <p><b>목록 벤더 스냅샷({@code culture_list_snapshot})도 이 record를 원천으로 적재한다</b> — 별도의 원문 verbatim
 * 어휘를 함께 나르지 않는다(같은 12필드를 두 벌 들고 다니는 중복이었다). 대가: {@code parseDate}·
 * {@code parseCoordinate}가 실패한 값은 null이라 "원천이 뭐라고 했나"가 그 행에서는 남지 않는다(수용 — 사용자 결정).
 * 상세({@link DetailFetch})는 {@code contents1} 원문이 평문 추출로 복구 불가라 verbatim 어휘를 유지한다.
 */
public record CatalogExhibitionData(
		String externalId,
		String title,
		String place,
		LocalDate startDate,
		LocalDate endDate,
		ExhibitionRegion region,
		ExhibitionCategory category,
		String posterUrl,
		String detailUrl,
		String serviceName,
		Double gpsX,
		Double gpsY,
		String sigungu,
		String realmName,
		String areaText) {

	/** 원천 식별자·제목이 없는 행은 적재 불가 — 유효한 수집 데이터만 통과시킨다. */
	public boolean isPersistable() {
		return externalId != null && !externalId.isBlank() && title != null && !title.isBlank();
	}
}
