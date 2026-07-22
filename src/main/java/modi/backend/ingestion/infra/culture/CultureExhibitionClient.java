package modi.backend.ingestion.infra.culture;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import lombok.RequiredArgsConstructor;
import modi.backend.domain.exhibition.catalog.ExhibitionErrorCode;
import modi.backend.domain.exhibition.catalog.ExhibitionRegion;
import modi.backend.ingestion.domain.data.CatalogFetchCriteria;
import modi.backend.ingestion.domain.data.CatalogPage;
import modi.backend.ingestion.domain.data.CultureDetailPayload;
import modi.backend.ingestion.domain.port.ExhibitionCatalogClient;
import modi.backend.ingestion.domain.data.CatalogFetchFilter;
import modi.backend.ingestion.properties.PublicDataProperties;
import modi.backend.support.error.CoreException;

/**
 * 한눈에보는문화정보(15138937) realm2(목록 1페이지)·detail2(상세) <b>단건 호출</b> 담당.
 * <p>
 * 요청선 조립({@link RestClient})·응답 수신·예외 변환까지가 이 클래스의 일이다.
 * <b>페이지 순회는 여기 없다</b> — 콜 하나하나가 감사·조기 종료의 단위라 {@code CatalogSynchronizer}가 순회한다.
 * 이 클래스는 <b>한 페이지·한 건</b>을 부르고 도메인 어휘로 돌려주는 데까지다(포트 구현 = DIP).
 * <p>
 * 응답(XML)은 Spring의 XML 메시지 컨버터가 응답 record로 곧바로 역직렬화하고, 벤더 실패 판정은
 * {@link CultureApiErrorHandler}에 위임하고, 도메인 매핑은 응답 record의 {@code toCatalog()}·{@code toDetail()}이 한다(SRP).
 * 통신 실패·비정상 응답은 {@link ExhibitionErrorCode#EXTERNAL_API_UNAVAILABLE}로 변환한다(HTTP·라이브러리 예외 누수 차단).
 * <p>
 * <b>벤더 코드 번역 스위치문이 없다</b> — 분야·정렬·분야별구분은 각 enum이 자기 코드를 들고 있고, 지역은
 * {@link ExhibitionRegion#areaText()}가 대표 표기를 준다. 상수를 더하면서 매핑을 빠뜨리는 실수가 불가능하다.
 * <p>
 * <b>호출 감사·스냅샷 적재는 여기서 하지 않는다</b>: 이 클래스의 책임은 "불러서 응답을 준다"까지다(사용자 결정).
 * 리포지토리가 하나도 주입되지 않는다.
 */
@Component
@RequiredArgsConstructor
public class CultureExhibitionClient implements ExhibitionCatalogClient {

	private static final Logger log = LoggerFactory.getLogger(CultureExhibitionClient.class);
	/** 원천의 기간 파라미터(from·to) 표기. */
	private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

	/** 필드명이 곧 빈 이름이다 — RestClient 빈이 여럿이라 이름으로 해소된다(@Qualifier 대체). */
	private final RestClient koreaCultureInformationClient;
	/** 벤더 실패(200 + resultCode) 판정 — 상태코드로는 잡을 수 없어 본문을 봐야 한다. */
	private final CultureApiErrorHandler errorHandler;
	private final PublicDataProperties properties;

	/** 외부 호출이 가능한 상태인가(인증키·base URL 설정됨). 접속 설정은 이 클래스 밖으로 새지 않는다. */
	@Override
	public boolean isConfigured() {
		return properties.isConfigured();
	}

	/**
	 * 목록 <b>한 페이지</b>를 가져온다 — 전송과 응답 검증만 한다.
	 * 전체 순회도, 호출 감사도 {@link CultureCatalogReader}의 몫이다.
	 */
	@Override
	public CatalogPage fetchPage(CatalogFetchCriteria criteria, int page) {
		KoreaCultureDto.Realm2ListResponse response = fetchListPage(criteria, page);
		return new CatalogPage(
				response.items().stream().map(KoreaCultureDto.Realm2ListResponse.Item::toCatalog).toList(),
				response.totalCount());
	}

	/**
	 * 상세 1건 — 응답을 도메인 계약({@link CultureDetailPayload})으로 돌려준다.
	 * <b>적재도 감사도 하지 않는다</b>: 스냅샷 적재와 호출 감사는 호출부(application)의 몫이다.
	 */
	@Override
	public CultureDetailPayload fetchDetail(String externalId) {
		return fetchExhibitionDetail(externalId).items().get(0);
	}

	/** 원문 응답 그대로 — 같은 패키지의 수동 확인 테스트가 응답 구조를 들여다볼 수 있게 package-private으로 둔다. */
	KoreaCultureDto.Realm2ListResponse fetchListPage(CatalogFetchCriteria criteria, int page) {
		String realmCode = criteria.realm().code();
		try {
			CatalogFetchFilter filter = criteria.filter();
			KoreaCultureDto.Realm2ListResponse response = koreaCultureInformationClient.get()
					.uri(uriBuilder -> uriBuilder.path("/realm2")
							// 필수 — 인증·페이징·분야·분야별구분·정렬
							.queryParam("serviceKey", properties.serviceKey())
							.queryParam("PageNo", page)
							.queryParam("numOfrows", criteria.pageSize())
							.queryParam("realmCode", realmCode)
							.queryParam("serviceTp", criteria.serviceType().code())
							.queryParam("sortStdr", criteria.sortOrder().code())
							// 선택 필터 — 값이 없으면 파라미터 자체가 붙지 않는다.
							.queryParamIfPresent("sido",
									Optional.ofNullable(filter.region()).map(ExhibitionRegion::areaText))
							.queryParamIfPresent("from", Optional.ofNullable(filter.from()).map(YYYYMMDD::format))
							.queryParamIfPresent("to", Optional.ofNullable(filter.to()).map(YYYYMMDD::format))
							.queryParamIfPresent("place", Optional.ofNullable(filter.place()))
							.queryParamIfPresent("keyword", Optional.ofNullable(filter.keyword()))
							.queryParamIfPresent("gpsxfrom",
									Optional.ofNullable(filter.bounds()).map(CatalogFetchFilter.Bounds::westLongitude))
							.queryParamIfPresent("gpsyfrom",
									Optional.ofNullable(filter.bounds()).map(CatalogFetchFilter.Bounds::southLatitude))
							.queryParamIfPresent("gpsxto",
									Optional.ofNullable(filter.bounds()).map(CatalogFetchFilter.Bounds::eastLongitude))
							.queryParamIfPresent("gpsyto",
									Optional.ofNullable(filter.bounds()).map(CatalogFetchFilter.Bounds::northLatitude))
							.build())
					.retrieve()
					.body(KoreaCultureDto.Realm2ListResponse.class);
			errorHandler.throwIfVendorError(response);
			return response;
		} catch (RuntimeException e) {
			log.warn("외부 전시 API 호출 실패 /realm2: {}", e.getMessage());
			// 이미 CoreException이면 그대로 던진다 — CultureApiErrorHandler의 "비정상 코드(한도초과 vs 키오류)" 메시지를
			// 일반 문구로 덮으면 운영 로그에서 원인 판독이 불가능해진다.
			if (e instanceof CoreException coreException) {
				throw coreException;
			}
			throw new CoreException(ExhibitionErrorCode.EXTERNAL_API_UNAVAILABLE, "외부 전시 API 호출 실패", e);
		}
	}

	/**
	 * 상세 1건을 벤더 원문과 함께 가져온다 — 상세는 페이징이 없어 단건 호출이 곧 결과다.
	 * 인증키 미설정·원천에 상세 없음은 빈 Optional.
	 */
	private KoreaCultureDto.Detail2Response fetchExhibitionDetail(String externalId) {
		if (!properties.isConfigured()) {
			throw new CoreException(ExhibitionErrorCode.EXTERNAL_API_UNAVAILABLE, "외부 전시 API 인증키 미설정");
		}
		try {
			KoreaCultureDto.Detail2Response response = koreaCultureInformationClient.get()
					.uri(uriBuilder -> uriBuilder.path("/detail2")
							.queryParam("serviceKey", properties.serviceKey())
							.queryParam("seq", externalId)
							.build())
					.retrieve()
					.body(KoreaCultureDto.Detail2Response.class);
			errorHandler.throwIfVendorError(response);
			List<KoreaCultureDto.Detail2Response.Item> items = response.items();
			if (items.isEmpty()) {
				// resultCode=00인데 items가 빈 응답 — 없는 seq에서 실제로 나온다(실측 2026-07-21).
				// 정상 운영에선 목록에서 받은 seq만 부르므로, 이건 "목록 이후 원천에서 삭제됨"을 뜻한다.
				throw new CoreException(ExhibitionErrorCode.EXTERNAL_API_UNAVAILABLE,
						"외부 전시 API 상세 없음(원천에서 사라진 전시): seq=" + externalId);
			}
			return response;
		} catch (RuntimeException e) {
			log.warn("외부 전시 API 호출 실패 /detail2: {}", e.getMessage());
			if (e instanceof CoreException coreException) {
				throw coreException;
			}
			throw new CoreException(ExhibitionErrorCode.EXTERNAL_API_UNAVAILABLE, "외부 전시 API 호출 실패", e);
		}
	}

}
