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
import modi.backend.ingestion.domain.data.CatalogFetchFilter;
import modi.backend.ingestion.domain.data.DetailFetch;
import modi.backend.ingestion.properties.PublicDataProperties;
import modi.backend.support.error.CoreException;

/**
 * 한눈에보는문화정보(15138937) realm2(목록 1페이지)·detail2(상세) <b>단건 호출</b> 담당.
 * <p>
 * 요청선 조립({@link RestClient})·응답 수신·예외 변환까지가 이 클래스의 일이다.
 * <b>페이징(어디까지 부를지)과 접기는 {@link CultureCatalogReader}의 몫</b>이라 여기 없다 —
 * 그래서 요청선이 바뀔 때만 이 파일이 바뀐다.
 * <p>
 * 응답(XML)은 Spring의 XML 메시지 컨버터가 응답 record로 곧바로 역직렬화하고, 정상 응답 여부
 * 판정과 도메인 매핑은 {@link CultureApiMapper}에 위임한다(SRP).
 * 통신 실패·비정상 응답은 {@link ExhibitionErrorCode#EXTERNAL_API_UNAVAILABLE}로 변환한다(HTTP·라이브러리 예외 누수 차단).
 * <p>
 * <b>벤더 코드 번역 스위치문이 없다</b> — 분야·정렬·분야별구분은 각 enum이 자기 코드를 들고 있고, 지역은
 * {@link ExhibitionRegion#areaText()}가 대표 표기를 준다. 상수를 더하면서 매핑을 빠뜨리는 실수가 불가능하다.
 * <p>
 * <b>호출 감사는 여기서 하지 않는다</b>: 이 클래스의 책임은 "불러서 응답을 준다"까지다(사용자 결정).
 * 감사 행은 바로 위 호출자인 {@link CultureCatalogReader}가 남긴다 — 거기도 <b>페이지 단위</b> 호출자라
 * "재시도 1건이 3콜"처럼 호출 하나하나를 그대로 표현할 수 있다(더 위로 올리면 3콜이 1행으로 뭉개진다).
 */
@Component
@RequiredArgsConstructor
public class CultureExhibitionClient {

	private static final Logger log = LoggerFactory.getLogger(CultureExhibitionClient.class);
	/** 원천의 기간 파라미터(from·to) 표기. */
	private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

	/** 필드명이 곧 빈 이름이다 — RestClient 빈이 여럿이라 이름으로 해소된다(@Qualifier 대체). */
	private final RestClient koreaCultureInformationClient;
	private final CultureApiMapper mapper;
	private final PublicDataProperties properties;

	/** 외부 호출이 가능한 상태인가(인증키·base URL 설정됨). 접속 설정은 이 클래스 밖으로 새지 않는다. */
	public boolean isConfigured() {
		return properties.isConfigured();
	}

	/**
	 * 목록 <b>한 페이지</b>를 가져온다 — 전송과 응답 검증만 한다.
	 * 전체 순회도, 호출 감사도 {@link CultureCatalogReader}의 몫이다.
	 */
	public CultureRealmListResponse fetchListPage(CatalogFetchCriteria criteria, int page) {
		String realmCode = criteria.realm().code();
		try {
			CatalogFetchFilter filter = criteria.filter();
			CultureRealmListResponse response = koreaCultureInformationClient.get()
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
					.body(CultureRealmListResponse.class);
			mapper.verify(response);
			return response;
		} catch (RuntimeException e) {
			log.warn("외부 전시 API 호출 실패 /realm2: {}", e.getMessage());
			// 이미 CoreException이면 그대로 던진다 — CultureApiMapper의 "비정상 코드(한도초과 vs 키오류)" 메시지를
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
	public Optional<DetailFetch> fetchDetailSnapshot(String externalId) {
		if (!properties.isConfigured()) {
			return Optional.empty();
		}
		try {
			CultureDetailResponse response = koreaCultureInformationClient.get()
					.uri(uriBuilder -> uriBuilder.path("/detail2")
							.queryParam("serviceKey", properties.serviceKey())
							.queryParam("seq", externalId)
							.build())
					.retrieve()
					.body(CultureDetailResponse.class);
			mapper.verify(response);
			List<CultureDetailResponse.Item> items = response.items();
			if (items.isEmpty()) {
				// 호출은 정상인데 원천에 상세가 없다 — 실패가 아니라 원천의 사실이다(재조회해도 소용없다).
				return Optional.empty();
			}
			CultureDetailResponse.Item item = items.get(0);
			return Optional.of(new DetailFetch(mapper.toDetail(item), mapper.vendorOf(item)));
		} catch (RuntimeException e) {
			log.warn("외부 전시 API 호출 실패 /detail2: {}", e.getMessage());
			if (e instanceof CoreException coreException) {
				throw coreException;
			}
			throw new CoreException(ExhibitionErrorCode.EXTERNAL_API_UNAVAILABLE, "외부 전시 API 호출 실패", e);
		}
	}

}
