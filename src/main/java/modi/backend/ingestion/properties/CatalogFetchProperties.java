package modi.backend.ingestion.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import modi.backend.ingestion.domain.CatalogServiceType;
import modi.backend.ingestion.domain.CatalogSortOrder;
import modi.backend.ingestion.domain.ExhibitionRealm;
import modi.backend.ingestion.domain.data.CatalogFetchCriteria;
import modi.backend.ingestion.domain.data.CatalogFetchFilter;

/**
 * 전시 목록 수집 <b>요청 정책</b> 설정. {@code app.ingestion.exhibition-catalog.*} 바인딩.
 * <p>
 * 접속 설정({@link PublicDataProperties} — base URL·인증키·타임아웃)과 갈라 둔 이유는 소유 계층이 다르기 때문이다.
 * 여기 값들은 원천에 <b>무엇을 얼마나 요청할지</b>라서 수집 유스케이스({@code CatalogSynchronizer})가 읽어
 * {@link CatalogFetchCriteria}로 어댑터에 내려보낸다. 어댑터가 직접 읽지 않는다.
 *
 * @param realm       수집 대상 분야(기본 전시). 벤더 분야 코드로의 번역은 어댑터가 한다.
 * @param serviceType 분야별 구분(필수, 기본 공연/전시) — 원천 {@code serviceTp}.
 * @param sortOrder   정렬 기준(필수, 기본 시작일 오름차순) — 원천 {@code sortStdr}.
 * @param pageSize    1회 호출당 행 수(원천 {@code numOfrows}). 결과가 아니라 호출 횟수를 좌우한다.
 * @param maxItems    한 실행의 수집 상한(폭주 방지). 2026-07-15 실측 원천 총량 280건 대비 여유를 둔 값이며,
 *                 원천이 이 값을 넘기면 {@code sync_run.truncated}로 드러난다.
 */
@ConfigurationProperties(prefix = "app.ingestion.exhibition-catalog")
public record CatalogFetchProperties(ExhibitionRealm realm, CatalogServiceType serviceType,
		CatalogSortOrder sortOrder, Integer pageSize, Integer maxItems) {

	public CatalogFetchProperties {
		if (realm == null) {
			realm = ExhibitionRealm.EXHIBITION;
		}
		if (serviceType == null) {
			serviceType = CatalogServiceType.PERFORMANCE_EXHIBITION;
		}
		if (sortOrder == null) {
			// 원천이 정렬 미지정일 때와 같은 결과라 기본값으로 두어도 동작이 바뀌지 않는다.
			sortOrder = CatalogSortOrder.START_DATE_ASC;
		}
		if (pageSize == null || pageSize <= 0) {
			pageSize = 100;
		}
		if (maxItems == null || maxItems <= 0) {
			maxItems = 500;
		}
	}

	public CatalogFetchCriteria toCriteria() {
		return new CatalogFetchCriteria(realm, serviceType, sortOrder, pageSize, maxItems,
				CatalogFetchFilter.none());
	}
}
