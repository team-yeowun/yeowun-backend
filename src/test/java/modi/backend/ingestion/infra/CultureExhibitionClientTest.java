package modi.backend.ingestion.infra;

import modi.backend.ingestion.infra.culture.CultureApiErrorHandler;
import modi.backend.ingestion.domain.entity.CultureDetailSnapshot;
import modi.backend.ingestion.infra.culture.CultureExhibitionClient;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import modi.backend.ingestion.domain.CatalogServiceType;
import modi.backend.ingestion.domain.CatalogSortOrder;
import modi.backend.ingestion.domain.ExhibitionRealm;
import modi.backend.ingestion.domain.data.CatalogFetchCriteria;
import modi.backend.ingestion.domain.data.CatalogFetchFilter;
import modi.backend.ingestion.properties.PublicDataProperties;
import modi.backend.domain.exhibition.catalog.CatalogDetailData;
import modi.backend.ingestion.domain.data.CatalogExhibitionData;
import modi.backend.ingestion.domain.data.CatalogPage;
import modi.backend.domain.exhibition.catalog.ExhibitionRegion;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

/**
 * {@link CultureExhibitionClient} — 실 HTTP(MockWebServer)로 realm2 목록·detail2 상세 파싱/매핑을 검증한다.
 * XML 샘플은 {@link CultureApiResponseTest}의 검증 표본(seq=319005, 부산/사하구)을 재사용한다.
 */
class CultureExhibitionClientTest {

	private static final String REALM2_XML = "<response><header><resultCode>00</resultCode><resultMsg>정상입니다.</resultMsg></header>"
			+ "<body><totalCount>1</totalCount><PageNo>1</PageNo><numOfrows>100</numOfrows><items>"
			+ "<item><serviceName>전시</serviceName><seq>319005</seq><title>패트릭 블랑</title>"
			+ "<startDate>20180616</startDate><endDate>20281231</endDate><place>부산현대미술관</place>"
			+ "<realmName>전시</realmName><area>부산</area><sigungu>사하구</sigungu>"
			+ "<thumbnail>http://t/x.jpg</thumbnail><gpsX>128.9</gpsX><gpsY>35.1</gpsY></item>"
			+ "</items></body></response>";

	private static final String DETAIL2_XML = "<response><header><resultCode>00</resultCode><resultMsg>정상입니다.</resultMsg></header>"
			+ "<body><totalCount>1</totalCount><items>"
			+ "<item><seq>319005</seq><title>패트릭 블랑</title><price>무료</price>"
			+ "<contents1>설명입니다.</contents1><url>http://detail/319005</url><phone>051-000-0000</phone>"
			+ "<imgUrl>http://img/x.jpg</imgUrl><placeUrl>http://place/x</placeUrl>"
			+ "<placeAddr>부산광역시 사하구 낙동남로 1191</placeAddr><placeSeq>P1</placeSeq></item>"
			+ "</items></body></response>";


	/** 수집 조건은 이제 호출자가 정한다 — 종전 설정값(num-of-rows 100 · max-pages 5)과 동일한 상한. */
	private static final CatalogFetchCriteria CRITERIA =
			CatalogFetchCriteria.of(ExhibitionRealm.EXHIBITION, 100, 500);

	private MockWebServer server;
	private CultureExhibitionClient client;

	@BeforeEach
	void setUp() throws IOException {
		server = new MockWebServer();
		server.start();
		String baseUrl = "http://localhost:" + server.getPort();
		// 운영 조립과 동일: JDK 팩토리 고정(클래스패스의 Apache 자동감지 → 전송 재시도 방지).
		// 메시지 컨버터는 기본값 그대로 — configureMessageConverters를 부르면 XML 컨버터가 빠져 바인딩이 깨진다.
		RestClient restClient = RestClient.builder().baseUrl(baseUrl)
				.requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory())
				.build();
		PublicDataProperties properties = new PublicDataProperties(baseUrl, "test-service-key", 15L);
		// 테스트 대상은 포트 구현(Reader) — 단건 호출 Client를 감싸 페이징·접기까지 함께 검증한다.
		client = new CultureExhibitionClient(restClient, new CultureApiErrorHandler(), properties);
	}

	@AfterEach
	void tearDown() throws IOException {
		server.shutdown();
	}

	@Test
	@DisplayName("fetchPage — realm2 XML 파싱, area→region=BUSAN·sigungu·realmName·areaText 매핑")
	void fetchAll_realm2_파싱() {
		server.enqueue(new MockResponse().setBody(REALM2_XML).addHeader("Content-Type", "application/xml"));

		List<CatalogExhibitionData> result = client.fetchPage(CRITERIA, 1).items();

		assertThat(result).hasSize(1);
		CatalogExhibitionData item = result.get(0);
		assertThat(item.externalId()).isEqualTo("319005");
		assertThat(item.region()).isEqualTo(ExhibitionRegion.BUSAN);
		assertThat(item.sigungu()).isEqualTo("사하구");
		assertThat(item.realmName()).isEqualTo("전시");
		assertThat(item.areaText()).isEqualTo("부산");
	}

	@Test
	@DisplayName("fetchDetail — detail2 XML 파싱, price·placeAddr·placeSeq 매핑")
	void fetchDetail_detail2_파싱() {
		server.enqueue(new MockResponse().setBody(DETAIL2_XML).addHeader("Content-Type", "application/xml"));

		CatalogDetailData result = client.fetchDetail("319005").toDetail();

		assertThat(result.price()).isEqualTo("무료");
		assertThat(result.placeAddr()).isEqualTo("부산광역시 사하구 낙동남로 1191");
		assertThat(result.placeSeq()).isEqualTo("P1");
	}

	@Test
	@DisplayName("fetchPage — 응답 아이템의 필드가 끝까지 실려 나온다(이 record가 스냅샷 적재 원천이다)")
	void fetchAll_전필드_동승() {
		server.enqueue(new MockResponse().setBody(REALM2_XML).addHeader("Content-Type", "application/xml"));

		List<CatalogExhibitionData> result = client.fetchPage(CRITERIA, 1).items();

		assertThat(result.get(0).externalId()).isEqualTo("319005");
		assertThat(result.get(0).gpsY()).isEqualTo(35.1); // 마지막 필드까지 온전히 담긴다
	}

	@Test
	@DisplayName("fetchPage — 여러 아이템이면 각자 자기 값을 받는다(A의 값이 B에 붙지 않는다)")
	void fetchAll_아이템별_짝짓기() {
		String twoItems = "<response><header><resultCode>00</resultCode><resultMsg>정상</resultMsg></header>"
				+ "<body><totalCount>2</totalCount><items>"
				+ "<item><seq>1001</seq><title>첫째</title><area>서울</area></item>"
				+ "<item><seq>1002</seq><title>둘째</title><area>부산</area></item>"
				+ "</items></body></response>";
		server.enqueue(new MockResponse().setBody(twoItems).addHeader("Content-Type", "application/xml"));

		List<CatalogExhibitionData> result = client.fetchPage(CRITERIA, 1).items();

		// 짝이 밀리면 스냅샷이 통째로 오염된다 — 없는 것보다 나쁘다.
		assertThat(result).hasSize(2);
		assertThat(result.get(0).externalId()).isEqualTo("1001");
		assertThat(result.get(0).title()).isEqualTo("첫째");
		assertThat(result.get(1).externalId()).isEqualTo("1002");
		assertThat(result.get(1).title()).isEqualTo("둘째");
	}

	@Test
	@DisplayName("총 건수 — 원천이 말한 값을 그대로 싣는다(없으면 null = \"모른다\", 0이 아니다)")
	void 총건수_원천값_전달() {
		server.enqueue(page(5, "1001", "1002"));
		server.enqueue(page(5, "2001", "2002"));

		CatalogPage result = client.fetchPage(CatalogFetchCriteria.of(ExhibitionRealm.EXHIBITION, 2, 4), 1);

		assertThat(result.totalCount()).isEqualTo(5);
	}

	@Test
	@DisplayName("총 건수 — 응답에 없으면 null(0으로 뭉개지 않는다)")
	void 총건수_결측이면_null() {
		server.enqueue(pageWithoutTotalCount("1001", "1002"));
		server.enqueue(pageWithoutTotalCount("2001", "2002"));

		CatalogPage result = client.fetchPage(CatalogFetchCriteria.of(ExhibitionRealm.EXHIBITION, 2, 4), 1);

		assertThat(result.totalCount()).isNull(); // 모른다(0이 아니다)
	}

	@Test
	@DisplayName("적재 불가 행도 걸러내지 않고 그대로 싣는다 — 마지막 페이지·조기 종료 판정이 이 수에 달려 있다")
	void 적재불가행_필터_안함() {
		// 원천이 3건을 줬는데 그중 1건은 title이 없어 적재 불가다.
		String body = "<response><header><resultCode>00</resultCode><resultMsg>정상</resultMsg></header>"
				+ "<body><totalCount>3</totalCount><items>"
				+ "<item><seq>1001</seq><title>첫째</title><area>서울</area></item>"
				+ "<item><seq>1002</seq><title></title><area>서울</area></item>"
				+ "<item><seq>1003</seq><title>셋째</title><area>부산</area></item>"
				+ "</items></body></response>";
		server.enqueue(new MockResponse().setBody(body).addHeader("Content-Type", "application/xml"));

		CatalogPage result = client.fetchPage(CatalogFetchCriteria.of(ExhibitionRealm.EXHIBITION, 3, 3), 1);

		// 어댑터가 미리 걸러 주면 불량 행이 낀 꽉 찬 페이지가 "덜 찬 페이지"로 보여 순회가 조기에 끊기고,
		// 걸러진 seq가 빠져 "전량 known" 판정도 죽는다. 필터는 CatalogSynchronizer가 건다.
		assertThat(result.items()).hasSize(3);
		assertThat(result.totalCount()).isEqualTo(3);
	}

	private MockResponse page(int totalCount, String... seqs) {
		return new MockResponse().setBody(itemsXml("<totalCount>" + totalCount + "</totalCount>", seqs))
				.addHeader("Content-Type", "application/xml");
	}

	private MockResponse pageWithoutTotalCount(String... seqs) {
		return new MockResponse().setBody(itemsXml("", seqs)).addHeader("Content-Type", "application/xml");
	}

	private String itemsXml(String totalCountTag, String... seqs) {
		StringBuilder xml = new StringBuilder(
				"<response><header><resultCode>00</resultCode><resultMsg>정상</resultMsg></header><body>")
				.append(totalCountTag).append("<items>");
		for (String seq : seqs) {
			xml.append("<item><seq>").append(seq).append("</seq><title>전시").append(seq)
					.append("</title><area>서울</area></item>");
		}
		return xml.append("</items></body></response>").toString();
	}

	@Test
	@DisplayName("선택 필터 — 값이 없는 파라미터는 요청선에 아예 붙지 않는다")
	void 선택필터_없으면_파라미터_미포함() throws InterruptedException {
		server.enqueue(new MockResponse().setBody(REALM2_XML).addHeader("Content-Type", "application/xml"));

		client.fetchPage(CRITERIA, 1); // 필터 없음

		String path = server.takeRequest().getPath();
		// 빈 값을 보내면 원천이 0건을 준다 — "안 보내는 것"과 "빈 값을 보내는 것"은 다르다.
		assertThat(path).doesNotContain("sido", "from", "to", "place", "keyword",
				"gpsxfrom", "gpsyfrom", "gpsxto", "gpsyto");
		// 정렬·분야별구분은 필수라 필터가 비어도 항상 실린다.
		assertThat(path).contains("sortStdr=8").contains("serviceTp=A");
	}

	@Test
	@DisplayName("선택 필터 — 채운 값만 벤더 파라미터로 번역되어 붙는다(지역·기간·정렬)")
	void 선택필터_채운값만_번역되어_포함() throws InterruptedException {
		server.enqueue(new MockResponse().setBody(REALM2_XML).addHeader("Content-Type", "application/xml"));
		CatalogFetchFilter filter = new CatalogFetchFilter(
				ExhibitionRegion.BUSAN, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), null, null, null);

		client.fetchPage(new CatalogFetchCriteria(ExhibitionRealm.EXHIBITION,
				CatalogServiceType.PERFORMANCE_EXHIBITION, CatalogSortOrder.TITLE_ASC, 100, 500, filter), 1);

		String path = server.takeRequest().getPath();
		// 도메인 어휘 → 벤더 표기 번역이 여기서 확정된다(BUSAN→부산, LocalDate→YYYYMMDD, REGISTERED→1).
		assertThat(path).contains("sido=%EB%B6%80%EC%82%B0"); // "부산" URL 인코딩
		assertThat(path).contains("from=20260101").contains("to=20261231");
		assertThat(path).contains("sortStdr=2"); // TITLE → 2
		// 안 채운 것은 여전히 안 붙는다.
		assertThat(path).doesNotContain("place", "keyword", "gpsxfrom");
	}

	@Test
	@DisplayName("선택 필터 — 좌표 범위는 네 파라미터가 함께 붙는다(셋만 채운 반쪽 요청 불가)")
	void 선택필터_좌표범위_네개_동시() throws InterruptedException {
		server.enqueue(new MockResponse().setBody(REALM2_XML).addHeader("Content-Type", "application/xml"));
		CatalogFetchFilter filter = new CatalogFetchFilter(null, null, null, null, null,
				new CatalogFetchFilter.Bounds(128.8, 35.0, 129.2, 35.3));

		client.fetchPage(new CatalogFetchCriteria(ExhibitionRealm.EXHIBITION,
				CatalogServiceType.PERFORMANCE_EXHIBITION, CatalogSortOrder.START_DATE_ASC, 100, 500, filter), 1);

		String path = server.takeRequest().getPath();
		assertThat(path).contains("gpsxfrom=128.8").contains("gpsyfrom=35.0")
				.contains("gpsxto=129.2").contains("gpsyto=35.3");
	}

	@Test
	@DisplayName("요청선 — realm2/detail2 경로와 쿼리 파라미터 이름이 원천 스펙 그대로 나간다")
	void 요청선_경로와_쿼리파라미터() throws InterruptedException {
		server.enqueue(new MockResponse().setBody(REALM2_XML).addHeader("Content-Type", "application/xml"));
		server.enqueue(new MockResponse().setBody(DETAIL2_XML).addHeader("Content-Type", "application/xml"));

		client.fetchPage(CRITERIA, 1);
		client.fetchDetail("319005");

		// 파라미터 이름은 원천이 정한 것이라 대소문자 한 글자만 틀려도(PageNo·numOfrows) 응답이 빈다 —
		// 응답 파싱만 보는 테스트는 이 오류를 못 잡으므로 요청선 자체를 못박는다.
		assertThat(server.takeRequest().getPath())
				.isEqualTo("/realm2?serviceKey=test-service-key&PageNo=1&numOfrows=100&realmCode=D000"
						+ "&serviceTp=A&sortStdr=8");
		assertThat(server.takeRequest().getPath())
				.isEqualTo("/detail2?serviceKey=test-service-key&seq=319005");
	}
}
