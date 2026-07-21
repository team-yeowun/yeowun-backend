package modi.backend.ingestion.infra;

import modi.backend.ingestion.infra.culture.CultureApi;
import modi.backend.ingestion.infra.culture.CultureApiMapper;
import modi.backend.ingestion.infra.culture.CultureExhibitionClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import modi.backend.ingestion.properties.PublicDataProperties;
import modi.backend.ingestion.domain.ExternalApi;
import modi.backend.ingestion.domain.entity.ExternalApiCallLog;
import modi.backend.ingestion.domain.ExternalApiOutcome;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

/**
 * {@link CultureExhibitionClient}의 <b>외부 호출 감사</b>(이관 5단계) 검증 — 실 HTTP(MockWebServer).
 * <p>
 * 감사 행은 어댑터가 남기므로 여기가 유일한 검증 지점이다. 특히 <b>NO_DATA(호출은 정상인데 원천에 줄 게 없음)</b>와
 * FAILED(전송 오류)의 구분을 본다 — 현행은 외부 호출 기록이 아예 없어 "폴백이 왜 늘었나"·"오늘 몇 번 불렀나"를
 * 로그 grep으로만 알 수 있었다.
 * <p>
 * 감사 저장소는 인메모리 스텁으로 둔다(저장 자체는 {@code ExternalApiCallLogRepositoryImpl}의 책임 — REQUIRES_NEW).
 */
class CultureExhibitionClientAuditTest {

	private static final String REALM2_XML = "<response><header><resultCode>00</resultCode><resultMsg>정상</resultMsg></header>"
			+ "<body><totalCount>1</totalCount><items>"
			+ "<item><seq>319005</seq><title>패트릭 블랑</title><area>부산</area></item>"
			+ "</items></body></response>";

	private static final String EMPTY_DETAIL_XML = "<response><header><resultCode>00</resultCode><resultMsg>정상</resultMsg></header>"
			+ "<body><totalCount>0</totalCount><items/></body></response>";

	private MockWebServer server;
	private CultureExhibitionClient client;
	private List<ExternalApiCallLog> recorded;

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
		recorded = new ArrayList<>();
		client = new CultureExhibitionClient(cultureApi, new CultureApiMapper(), properties, call -> {
			recorded.add(call);
			return call;
		});
	}

	@AfterEach
	void tearDown() throws IOException {
		server.shutdown();
	}

	@Test
	@DisplayName("목록 호출 — 페이지마다 CULTURE_LIST 감사 행이 남는다(무료라 billable=false)")
	void 목록_호출_감사() {
		server.enqueue(new MockResponse().setBody(REALM2_XML).addHeader("Content-Type", "application/xml"));

		client.fetchAll();

		assertThat(recorded).hasSize(1);
		ExternalApiCallLog call = recorded.get(0);
		assertThat(call.getApi()).isEqualTo(ExternalApi.CULTURE_LIST);
		assertThat(call.getOutcome()).isEqualTo(ExternalApiOutcome.SUCCESS);
		assertThat(call.isBillable()).isFalse(); // 문화포털은 무료
		assertThat(call.getRequestKey()).contains("page=1");
		assertThat(call.getCalledAt()).isNotNull();
	}

	@Test
	@DisplayName("상세 — 원천에 상세가 없으면 NO_DATA로 남는다(실패가 아니라 원천의 사실)")
	void 상세_빈응답_NO_DATA() {
		server.enqueue(new MockResponse().setBody(EMPTY_DETAIL_XML).addHeader("Content-Type", "application/xml"));

		client.fetchDetail("319005");

		assertThat(recorded).hasSize(1);
		assertThat(recorded.get(0).getApi()).isEqualTo(ExternalApi.CULTURE_DETAIL);
		// NO_DATA와 FAILED를 뭉뚱그리면 "원천에 없는 전시"와 "우리가 못 부른 전시"가 섞여 재시도 판단이 불가능해진다.
		assertThat(recorded.get(0).getOutcome()).isEqualTo(ExternalApiOutcome.NO_DATA);
		assertThat(recorded.get(0).getRequestKey()).isEqualTo("319005");
	}

	@Test
	@DisplayName("상세 — 전송 오류는 FAILED로 남고 예외는 그대로 전파된다(기존 동작 불변)")
	void 상세_전송오류_FAILED() {
		server.enqueue(new MockResponse().setResponseCode(500));

		assertThatThrownBy(() -> client.fetchDetail("319005")).isInstanceOf(RuntimeException.class);

		assertThat(recorded).hasSize(1);
		assertThat(recorded.get(0).getOutcome()).isEqualTo(ExternalApiOutcome.FAILED);
	}

	@Test
	@DisplayName("감사 저장이 실패해도 수집은 깨지지 않는다(부가 기록이 본 기능을 멈추면 안 된다)")
	void 감사_실패해도_수집은_계속() {
		server.enqueue(new MockResponse().setBody(REALM2_XML).addHeader("Content-Type", "application/xml"));
		PublicDataProperties properties = new PublicDataProperties(
				"http://localhost:" + server.getPort(), "test-service-key", "D000", 100, 5, 15L);
		RestClient restClient = RestClient.builder().baseUrl("http://localhost:" + server.getPort())
				.requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory())
				.configureMessageConverters(b -> b.withStringConverter(
						new org.springframework.http.converter.StringHttpMessageConverter(
								java.nio.charset.StandardCharsets.UTF_8)))
				.build();
		CultureApi api = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient)).build()
				.createClient(CultureApi.class);
		CultureExhibitionClient failing = new CultureExhibitionClient(api, new CultureApiMapper(), properties,
				call -> {
					throw new IllegalStateException("감사 저장 실패");
				});

		assertThat(failing.fetchAll().items()).hasSize(1); // 수집은 정상
	}
}
