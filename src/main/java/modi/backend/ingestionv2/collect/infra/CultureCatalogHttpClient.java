package modi.backend.ingestionv2.collect.infra;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import modi.backend.application.audit.ExternalApiCallLogRecorder;
import modi.backend.domain.audit.ApiCallSource;
import modi.backend.domain.audit.ExternalApi;
import modi.backend.domain.audit.ExternalApiCallLog;
import modi.backend.domain.audit.ExternalApiOutcome;
import modi.backend.ingestionv2.collect.domain.CatalogItem;
import modi.backend.ingestionv2.collect.domain.CultureCatalogClient;
import modi.backend.ingestionv2.common.IngestionClock;

/**
 * 문화포털 목록(realm2) 조회 어댑터.
 *
 * <ul>
 *   <li>@Transactional 없음 (트랜잭션 밖 호출이 전제)</li>
 *   <li>감사 로그는 콜 단위 (페이지마다 한 행)</li>
 *   <li>중복 판정 없음 (멱등은 수집 트랜잭션의 유일 제약이 담당)</li>
 *   <li>순회 종료 조건 두 가지: 덜 찬 페이지, 수집 상한 도달</li>
 * </ul>
 */
@Component
public class CultureCatalogHttpClient implements CultureCatalogClient {

	/** 원천 분야 코드. 전시. */
	private static final String REALM_EXHIBITION = "D000";
	/** 분야별 구분. 공연과 전시. */
	private static final String SERVICE_TYPE_PERFORMANCE_EXHIBITION = "A";
	/** 정렬 기준. 등록 역순이라 신규 등록분이 먼저 온다. */
	private static final String SORT_REGISTRATION_DESC = "8";

	private final CultureApiCaller caller;
	private final ExternalApiCallLogRecorder callLogRecorder;
	private final int pageSize;
	private final int maxItems;

	public CultureCatalogHttpClient(
			CultureApiCaller caller,
			ExternalApiCallLogRecorder callLogRecorder,
			@Value("${app.ingestion.v2.catalog.page-size:100}") int pageSize,
			@Value("${app.ingestion.v2.catalog.max-items:500}") int maxItems) {
		this.caller = caller;
		this.callLogRecorder = callLogRecorder;
		this.pageSize = pageSize;
		this.maxItems = maxItems;
	}

	@Override
	public List<CatalogItem> fetchCatalog() {
		List<CatalogItem> collected = new ArrayList<>();
		int maxCalls = (maxItems + pageSize - 1) / pageSize;
		for (int page = 1; page <= maxCalls; page++) {
			List<CultureApiDto.ListResponse.Item> items = fetchPage(page).items();
			items.stream().map(CultureApiDto.ListResponse.Item::toCatalogItem).forEach(collected::add);
			if (items.size() < pageSize) {
				break; // 덜 찬 페이지 = 마지막
			}
		}
		return collected.size() <= maxItems ? collected : List.copyOf(collected.subList(0, maxItems));
	}

	private CultureApiDto.ListResponse fetchPage(int page) {
		String requestKey = "realmCode=" + REALM_EXHIBITION + "&page=" + page;
		LocalDateTime calledAt = IngestionClock.now();
		try {
			CultureApiDto.ListResponse response = caller.list(
					REALM_EXHIBITION, SERVICE_TYPE_PERFORMANCE_EXHIBITION, SORT_REGISTRATION_DESC, page, pageSize);
			record(requestKey, ExternalApiOutcome.SUCCESS, calledAt);
			return response;
		} catch (RuntimeException failure) {
			record(requestKey, ExternalApiOutcome.FAILED, calledAt);
			throw failure;
		}
	}

	private void record(String requestKey, ExternalApiOutcome outcome, LocalDateTime calledAt) {
		callLogRecorder.record(ExternalApiCallLog.of(
				ApiCallSource.INGESTION, ExternalApi.CULTURE_LIST, requestKey, outcome, calledAt));
	}
}
