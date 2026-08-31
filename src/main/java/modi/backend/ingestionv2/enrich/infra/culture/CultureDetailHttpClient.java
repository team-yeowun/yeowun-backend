package modi.backend.ingestionv2.enrich.infra.culture;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import modi.backend.application.audit.ExternalApiCallLogRecorder;
import modi.backend.domain.audit.ApiCallSource;
import modi.backend.domain.audit.ExternalApi;
import modi.backend.domain.audit.ExternalApiCallLog;
import modi.backend.domain.audit.ExternalApiOutcome;
import modi.backend.ingestionv2.common.IngestionClock;
import modi.backend.ingestionv2.enrich.domain.detail.CultureDetailClient;
import modi.backend.ingestionv2.enrich.domain.detail.DetailData;

/**
 * 문화포털 상세(detail2) 조회 어댑터.
 *
 * <ul>
 *   <li>@Transactional 없음 (트랜잭션 밖 호출이 전제). 콜 1건 = 감사 1행(requestKey = vendorKey)</li>
 *   <li>정상 응답 + 빈 items = 원천에서 사라진 전시 - 예외가 아니라 DetailData.none()(감사 NO_DATA)</li>
 *   <li>전송 실패·벤더 실패만 예외 - 재시도 판정은 상위 몫</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class CultureDetailHttpClient implements CultureDetailClient {

	private final CultureDetailApiCaller caller;
	private final ExternalApiCallLogRecorder callLogRecorder;

	@Override
	public DetailData fetchDetail(String vendorKey) {
		LocalDateTime calledAt = IngestionClock.now();
		CultureDetailApiDto.DetailResponse response;
		try {
			response = caller.detail(vendorKey);
		} catch (RuntimeException failure) {
			record(vendorKey, ExternalApiOutcome.FAILED, calledAt);
			throw failure;
		}
		List<CultureDetailApiDto.DetailResponse.Item> items = response.items();
		if (items.isEmpty()) {
			record(vendorKey, ExternalApiOutcome.NO_DATA, calledAt);
			return DetailData.none();
		}
		record(vendorKey, ExternalApiOutcome.SUCCESS, calledAt);
		return items.get(0).toDetailData();
	}

	private void record(String vendorKey, ExternalApiOutcome outcome, LocalDateTime calledAt) {
		callLogRecorder.record(ExternalApiCallLog.of(
				ApiCallSource.INGESTION, ExternalApi.CULTURE_DETAIL, vendorKey, outcome, calledAt));
	}
}
