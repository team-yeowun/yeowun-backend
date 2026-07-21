package modi.backend.ingestion.infra.culture;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import modi.backend.ingestion.properties.PublicDataProperties;
import modi.backend.ingestion.domain.data.CatalogExhibitionData;
import modi.backend.ingestion.domain.data.CatalogListData;
import modi.backend.ingestion.domain.data.DetailFetch;
import modi.backend.ingestion.domain.port.ExhibitionCatalogClient;
import modi.backend.domain.exhibition.catalog.ExhibitionErrorCode;
import modi.backend.ingestion.domain.ExternalApi;
import modi.backend.ingestion.domain.entity.ExternalApiCallLog;
import modi.backend.ingestion.domain.port.ExternalApiCallLogRepository;
import modi.backend.ingestion.domain.ExternalApiOutcome;
import modi.backend.support.error.CoreException;

/**
 * {@link ExhibitionCatalogClient} 어댑터 — 한눈에보는문화정보(15138937) realm2(목록)·detail2(상세) 호출을 담당한다(DIP).
 * 호출 자체는 선언형 HTTP Interface {@link CultureApi}에 위임하고(KakaoApi와 동일 패턴), 응답이 XML이라 JSON 코덱 없이
 * 문자열로 받는다. XML 파싱·도메인 매핑은 {@link CultureApiMapper}에 위임하고, 이 클래스는 전송(HTTP 호출)과
 * 오케스트레이션(페이지네이션·인증키 미설정 스킵·전송 오류 변환)만 담당한다(SRP).
 * 통신 실패·비정상 응답은 {@link ExhibitionErrorCode#EXTERNAL_API_UNAVAILABLE}로 변환한다(HTTP·라이브러리 예외 누수 차단).
 * <p>
 * 호출마다 {@code external_api_call}에 감사 행을 남긴다(이관 5단계) — <b>어댑터가 직접 남기는 이유</b>는
 * "호출했다"가 전송 계층의 사실이라서다. 도메인 포트로 끌어올리면 재시도 1건이 3콜인 경우처럼
 * 호출부가 볼 수 없는 사건을 표현할 수 없다. 저장은 {@code REQUIRES_NEW}라 호출자 트랜잭션과 생사를 같이하지 않는다.
 */
@Component
@RequiredArgsConstructor
public class CultureExhibitionClient implements ExhibitionCatalogClient {

	private static final Logger log = LoggerFactory.getLogger(CultureExhibitionClient.class);

	private final CultureApi cultureApi;
	private final CultureApiMapper mapper;
	private final PublicDataProperties properties;
	/** 외부 호출 감사(append-only) — 문화포털은 무료라 billable=false. */
	private final ExternalApiCallLogRepository externalApiCallRepository;

	@Override
	public CatalogListData fetchAll() {
		if (!properties.isConfigured()) {
			// 인증키 미설정: 외부 호출을 시도하지 않고 스킵한다(데모는 시드 데이터로 동작 — 04_전시_구현.md).
			log.info("CULTURE_API_KEY 미설정 — 동기화 스킵");
			return CatalogListData.none();
		}
		List<CatalogExhibitionData> collected = new ArrayList<>();
		Integer totalCount = null;
		int seen = 0;
		boolean exhaustedPages = true; // 상한까지 다 돌았나(= break 없이 끝났나)
		for (int pageNo = 1; pageNo <= properties.maxPages(); pageNo++) {
			int page = pageNo;
			CultureApiResponse response = requestList(page);
			if (totalCount == null && response.body() != null) {
				totalCount = response.body().totalCount();
			}
			List<CultureApiResponse.Item> items = response.items();
			seen += items.size();
			// 원본(payload)은 아이템 객체에서 바로 직렬화한다 — 응답 문자열을 잘라 인덱스로 짝지을 필요가 없으므로
			// "A 전시의 원본이 B에 붙는" 오염이 원인부터 불가능하다.
			items.stream().map(mapper::toCatalog)
					.filter(CatalogExhibitionData::isPersistable).forEach(collected::add);
			if (items.size() < properties.numOfRows()) {
				exhaustedPages = false;
				break; // 마지막 페이지
			}
		}
		return new CatalogListData(collected, totalCount, truncated(totalCount, seen, exhaustedPages));
	}

	/**
	 * 조용한 절단 판정 — 원천에 더 있는데 상한({@code max-pages × num-of-rows})에 걸려 못 가져왔나.
	 * <p>
	 * 원천이 총 건수를 알려줬으면 <b>그 말과 우리가 본 수를 비교하는 게 가장 직접적인 증거</b>다.
	 * 총 건수를 모를 때만(응답에 없음) "상한까지 다 돌았는데 마지막 페이지가 꽉 찼다"는 간접 증거로 판정한다.
	 * 상한이 원천 크기와 정확히 같은 경우(예: 500건/500건) 간접 증거만으론 절단으로 오판하므로 순서가 중요하다.
	 */
	private boolean truncated(Integer totalCount, int seen, boolean exhaustedPages) {
		if (totalCount != null) {
			return seen < totalCount;
		}
		return exhaustedPages;
	}

	private CultureApiResponse requestList(int page) {
		LocalDateTime calledAt = LocalDateTime.now();
		String requestKey = "realmCode=" + properties.realmCode() + "&page=" + page;
		try {
			String xml = request("/realm2", () -> cultureApi.getRealmList(
					properties.serviceKey(), page, properties.numOfRows(), properties.realmCode()));
			CultureApiResponse response = mapper.parse(xml);
			record(ExternalApiCallLog.free(ExternalApi.CULTURE_LIST, requestKey, ExternalApiOutcome.SUCCESS, calledAt));
			return response;
		} catch (RuntimeException e) {
			record(ExternalApiCallLog.free(ExternalApi.CULTURE_LIST, requestKey, ExternalApiOutcome.FAILED, calledAt));
			throw e;
		}
	}

	@Override
	public Optional<DetailFetch> fetchDetailSnapshot(String externalId) {
		if (!properties.isConfigured()) {
			return Optional.empty();
		}
		LocalDateTime calledAt = LocalDateTime.now();
		try {
			String xml = request("/detail2", () -> cultureApi.getDetail(properties.serviceKey(), externalId));
			List<CultureApiResponse.Item> items = mapper.parse(xml).items();
			if (items.isEmpty()) {
				// 호출은 정상인데 원천에 상세가 없다 — 실패가 아니라 원천의 사실이다(재조회해도 소용없다).
				record(ExternalApiCallLog.free(ExternalApi.CULTURE_DETAIL, externalId, ExternalApiOutcome.NO_DATA,
						calledAt));
				return Optional.empty();
			}
			record(ExternalApiCallLog.free(ExternalApi.CULTURE_DETAIL, externalId, ExternalApiOutcome.SUCCESS, calledAt));
			CultureApiResponse.Item item = items.get(0);
			return Optional.of(new DetailFetch(mapper.toDetail(item), mapper.vendorOf(item)));
		} catch (RuntimeException e) {
			record(ExternalApiCallLog.free(ExternalApi.CULTURE_DETAIL, externalId, ExternalApiOutcome.FAILED, calledAt));
			throw e;
		}
	}

	/** 감사 기록은 부가 기능이다 — 여기서 실패해도 수집·적재를 깨지 않는다. */
	private void record(ExternalApiCallLog call) {
		try {
			externalApiCallRepository.save(call);
		} catch (RuntimeException e) {
			log.warn("외부 호출 감사 기록 실패(무시): {}", e.getMessage());
		}
	}

	private String request(String path, Supplier<String> call) {
		try {
			return call.get();
		} catch (RuntimeException e) {
			log.warn("외부 전시 API 호출 실패 {}: {}", path, e.getMessage());
			throw new CoreException(ExhibitionErrorCode.EXTERNAL_API_UNAVAILABLE, "외부 전시 API 호출 실패", e);
		}
	}
}
