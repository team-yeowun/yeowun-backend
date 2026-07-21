package modi.backend.ingestion.infra;

import modi.backend.ingestion.infra.culture.CultureApiMapper;
import modi.backend.ingestion.infra.culture.CultureCatalogReader;
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
import modi.backend.ingestion.domain.data.CatalogListData;
import modi.backend.ingestion.domain.data.DetailFetch;
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
	private CultureCatalogReader client;

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
		CultureApiMapper mapper = new CultureApiMapper();
		client = new CultureCatalogReader(
				// 감사 저장은 이 테스트의 관심사가 아니다(전송·파싱만 본다)
				new CultureExhibitionClient(restClient, mapper, properties, call -> call), mapper);
	}

	@AfterEach
	void tearDown() throws IOException {
		server.shutdown();
	}

	@Test
	@DisplayName("fetchAll — realm2 XML 파싱, area→region=BUSAN·sigungu·realmName·areaText 매핑")
	void fetchAll_realm2_파싱() {
		server.enqueue(new MockResponse().setBody(REALM2_XML).addHeader("Content-Type", "application/xml"));

		List<CatalogExhibitionData> result = client.fetchAll(CRITERIA).items();

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

		Optional<CatalogDetailData> result = client.fetchDetail("319005");

		assertThat(result).isPresent();
		assertThat(result.get().price()).isEqualTo("무료");
		assertThat(result.get().placeAddr()).isEqualTo("부산광역시 사하구 낙동남로 1191");
		assertThat(result.get().placeSeq()).isEqualTo("P1");
	}

	@Test
	@DisplayName("fetchAll — 각 아이템에 자기 응답의 벤더 원문(verbatim)이 실려 나온다")
	void fetchAll_벤더원문_동승() {
		server.enqueue(new MockResponse().setBody(REALM2_XML).addHeader("Content-Type", "application/xml"));

		List<CatalogExhibitionData> result = client.fetchAll(CRITERIA).items();

		assertThat(result.get(0).vendorItem().seq()).isEqualTo("319005");
		assertThat(result.get(0).vendorItem().gpsY()).isEqualTo("35.1"); // 마지막 필드까지 온전히 담긴다
	}

	@Test
	@DisplayName("fetchAll — 여러 아이템이면 각자 자기 원본을 받는다(A의 원본이 B에 붙지 않는다)")
	void fetchAll_payload_아이템별_짝짓기() {
		String twoItems = "<response><header><resultCode>00</resultCode><resultMsg>정상</resultMsg></header>"
				+ "<body><totalCount>2</totalCount><items>"
				+ "<item><seq>1001</seq><title>첫째</title><area>서울</area></item>"
				+ "<item><seq>1002</seq><title>둘째</title><area>부산</area></item>"
				+ "</items></body></response>";
		server.enqueue(new MockResponse().setBody(twoItems).addHeader("Content-Type", "application/xml"));

		List<CatalogExhibitionData> result = client.fetchAll(CRITERIA).items();

		// 짝이 밀리면 재파싱 원료가 통째로 오염된다 — 없는 것보다 나쁘다.
		assertThat(result).hasSize(2);
		assertThat(result.get(0).externalId()).isEqualTo("1001");
		assertThat(result.get(0).vendorItem().seq()).isEqualTo("1001");
		assertThat(result.get(1).externalId()).isEqualTo("1002");
		assertThat(result.get(1).vendorItem().seq()).isEqualTo("1002");
	}

	@Test
	@DisplayName("fetchDetailSnapshot — 상세 응답에도 벤더 원문이 실려 나온다(도메인 값과 같은 응답에서)")
	void fetchDetailSnapshot_벤더원문_동승() {
		server.enqueue(new MockResponse().setBody(DETAIL2_XML).addHeader("Content-Type", "application/xml"));

		Optional<DetailFetch> result = client.fetchDetailSnapshot("319005");

		assertThat(result.get().vendor().placeSeq()).isEqualTo("P1");
	}

	@Test
	@DisplayName("페이지 순회 — 덜 찬 페이지를 만나면 멈춘다(원천이 마지막 페이지를 명시하지 않으므로)")
	void 페이지순회_덜찬페이지에서_종료() throws InterruptedException {
		// pageSize=2 상한 3콜. 1페이지 꽉참(2건) → 2페이지 덜참(1건) → 여기서 멈춰야 한다.
		server.enqueue(page(2, "1001", "1002"));
		server.enqueue(page(2, "2001"));

		CatalogListData result = client.fetchAll(CatalogFetchCriteria.of(ExhibitionRealm.EXHIBITION, 2, 6));

		assertThat(result.items()).hasSize(3);
		assertThat(server.getRequestCount()).isEqualTo(2); // 3콜 상한이지만 2콜에서 멈춘다
		assertThat(result.truncated()).isFalse();
	}

	@Test
	@DisplayName("절단 — 원천이 말한 총 건수보다 덜 봤으면 truncated(상한에 걸린 조용한 절단)")
	void 절단_직접증거_총건수미달() {
		// 총 5건이라 하는데 상한이 2콜 × 2행 = 4건이라 다 못 가져온다.
		server.enqueue(page(5, "1001", "1002"));
		server.enqueue(page(5, "2001", "2002"));

		CatalogListData result = client.fetchAll(CatalogFetchCriteria.of(ExhibitionRealm.EXHIBITION, 2, 4));

		assertThat(result.totalCount()).isEqualTo(5);
		assertThat(result.truncated()).isTrue();
	}

	@Test
	@DisplayName("절단 아님 — 상한과 원천 크기가 정확히 같으면 다 가져온 것이다(간접 증거만으론 오판하는 경계)")
	void 절단아님_상한과_원천크기가_같을때() {
		// 총 4건 = 상한 4건. 마지막 페이지가 꽉 찬 채로 끝나므로 "덜 찬 페이지를 못 만났다"는 간접 증거는 절단이라 말한다.
		// 총 건수라는 직접 증거를 먼저 보기 때문에 오판하지 않는다.
		server.enqueue(page(4, "1001", "1002"));
		server.enqueue(page(4, "2001", "2002"));

		CatalogListData result = client.fetchAll(CatalogFetchCriteria.of(ExhibitionRealm.EXHIBITION, 2, 4));

		assertThat(result.items()).hasSize(4);
		assertThat(result.truncated()).isFalse();
	}

	@Test
	@DisplayName("절단 — 총 건수를 모르면 마지막 페이지가 꽉 찬 채 끝났는지로 판정한다(간접 증거)")
	void 절단_간접증거_총건수없음() {
		server.enqueue(pageWithoutTotalCount("1001", "1002"));
		server.enqueue(pageWithoutTotalCount("2001", "2002"));

		CatalogListData result = client.fetchAll(CatalogFetchCriteria.of(ExhibitionRealm.EXHIBITION, 2, 4));

		assertThat(result.totalCount()).isNull(); // 모른다(0이 아니다)
		assertThat(result.truncated()).isTrue();
	}

	@Test
	@DisplayName("절단 판정은 필터 이전 행 수로 한다 — 걸러진 불량 행을 절단으로 오인하지 않는다")
	void 절단판정_필터이전_행수기준() {
		// 원천이 3건을 줬고 총 건수도 3건인데, 그중 1건은 title이 없어 적재 불가로 걸러진다.
		// collected.size()(2)로 판정하면 2 < 3 이라 절단 거짓 양성이 난다.
		String body = "<response><header><resultCode>00</resultCode><resultMsg>정상</resultMsg></header>"
				+ "<body><totalCount>3</totalCount><items>"
				+ "<item><seq>1001</seq><title>첫째</title><area>서울</area></item>"
				+ "<item><seq>1002</seq><title></title><area>서울</area></item>"
				+ "<item><seq>1003</seq><title>셋째</title><area>부산</area></item>"
				+ "</items></body></response>";
		server.enqueue(new MockResponse().setBody(body).addHeader("Content-Type", "application/xml"));

		CatalogListData result = client.fetchAll(CatalogFetchCriteria.of(ExhibitionRealm.EXHIBITION, 3, 3));

		assertThat(result.items()).hasSize(2); // 불량 1건은 빠진다
		assertThat(result.truncated()).isFalse(); // 원천이 준 3건은 다 봤다
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

		client.fetchAll(CRITERIA); // 필터 없음

		String path = server.takeRequest().getPath();
		// 빈 값을 보내면 원천이 0건을 준다 — "안 보내는 것"과 "빈 값을 보내는 것"은 다르다.
		assertThat(path).doesNotContain("sido", "from", "to", "place", "keyword",
				"gpsxfrom", "gpsyfrom", "gpsxto", "gpsyto");
		// 정렬·분야별구분은 필수라 필터가 비어도 항상 실린다.
		assertThat(path).contains("sortStdr=1").contains("serviceTp=A");
	}

	@Test
	@DisplayName("선택 필터 — 채운 값만 벤더 파라미터로 번역되어 붙는다(지역·기간·정렬)")
	void 선택필터_채운값만_번역되어_포함() throws InterruptedException {
		server.enqueue(new MockResponse().setBody(REALM2_XML).addHeader("Content-Type", "application/xml"));
		CatalogFetchFilter filter = new CatalogFetchFilter(
				ExhibitionRegion.BUSAN, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), null, null, null);

		client.fetchAll(new CatalogFetchCriteria(ExhibitionRealm.EXHIBITION,
				CatalogServiceType.PERFORMANCE_EXHIBITION, CatalogSortOrder.TITLE_ASC, 100, 500, filter));

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

		client.fetchAll(new CatalogFetchCriteria(ExhibitionRealm.EXHIBITION,
				CatalogServiceType.PERFORMANCE_EXHIBITION, CatalogSortOrder.START_DATE_ASC, 100, 500, filter));

		String path = server.takeRequest().getPath();
		assertThat(path).contains("gpsxfrom=128.8").contains("gpsyfrom=35.0")
				.contains("gpsxto=129.2").contains("gpsyto=35.3");
	}

	@Test
	@DisplayName("요청선 — realm2/detail2 경로와 쿼리 파라미터 이름이 원천 스펙 그대로 나간다")
	void 요청선_경로와_쿼리파라미터() throws InterruptedException {
		server.enqueue(new MockResponse().setBody(REALM2_XML).addHeader("Content-Type", "application/xml"));
		server.enqueue(new MockResponse().setBody(DETAIL2_XML).addHeader("Content-Type", "application/xml"));

		client.fetchAll(CRITERIA);
		client.fetchDetail("319005");

		// 파라미터 이름은 원천이 정한 것이라 대소문자 한 글자만 틀려도(PageNo·numOfrows) 응답이 빈다 —
		// 응답 파싱만 보는 테스트는 이 오류를 못 잡으므로 요청선 자체를 못박는다.
		assertThat(server.takeRequest().getPath())
				.isEqualTo("/realm2?serviceKey=test-service-key&PageNo=1&numOfrows=100&realmCode=D000"
						+ "&serviceTp=A&sortStdr=1");
		assertThat(server.takeRequest().getPath())
				.isEqualTo("/detail2?serviceKey=test-service-key&seq=319005");
	}
}
