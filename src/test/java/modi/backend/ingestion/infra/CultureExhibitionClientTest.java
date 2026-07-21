package modi.backend.ingestion.infra;

import modi.backend.ingestion.infra.culture.CultureApi;
import modi.backend.ingestion.infra.culture.CultureApiMapper;
import modi.backend.ingestion.infra.culture.CultureExhibitionClient;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import modi.backend.ingestion.properties.PublicDataProperties;
import modi.backend.domain.exhibition.catalog.CatalogDetailData;
import modi.backend.ingestion.domain.data.CatalogExhibitionData;
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

	private MockWebServer server;
	private CultureExhibitionClient client;

	@BeforeEach
	void setUp() throws IOException {
		server = new MockWebServer();
		server.start();
		String baseUrl = "http://localhost:" + server.getPort();
		// 운영 조립(OAuthHttpClientConfig)과 동일: JDK 팩토리 고정(클래스패스의 Apache 자동감지 → 전송 재시도 방지) + UTF-8 String 컨버터
		RestClient restClient = RestClient.builder().baseUrl(baseUrl)
				.requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory())
				.configureMessageConverters(b -> b.withStringConverter(
						new org.springframework.http.converter.StringHttpMessageConverter(
								java.nio.charset.StandardCharsets.UTF_8)))
				.build();
		CultureApi cultureApi = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient)).build()
				.createClient(CultureApi.class);
		PublicDataProperties properties = new PublicDataProperties(baseUrl, "test-service-key", "D000", 100, 5, 15L);
		client = new CultureExhibitionClient(cultureApi, new CultureApiMapper(), properties,
				call -> call); // 감사 저장은 이 테스트의 관심사가 아니다(전송·파싱만 본다)
	}

	@AfterEach
	void tearDown() throws IOException {
		server.shutdown();
	}

	@Test
	@DisplayName("fetchAll — realm2 XML 파싱, area→region=BUSAN·sigungu·realmName·areaText 매핑")
	void fetchAll_realm2_파싱() {
		server.enqueue(new MockResponse().setBody(REALM2_XML).addHeader("Content-Type", "application/xml"));

		List<CatalogExhibitionData> result = client.fetchAll().items();

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

		List<CatalogExhibitionData> result = client.fetchAll().items();

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

		List<CatalogExhibitionData> result = client.fetchAll().items();

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
}
