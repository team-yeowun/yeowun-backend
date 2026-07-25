package modi.backend.ingestion.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;

import modi.backend.ingestion.infra.culture.KoreaCultureDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import modi.backend.ingestion.config.KoreaCultureInformationClientConfig;
import modi.backend.ingestion.domain.ExhibitionRealm;
import modi.backend.ingestion.domain.data.CatalogFetchCriteria;
import modi.backend.ingestion.domain.data.CatalogPage;
import modi.backend.ingestion.infra.culture.CultureApiErrorHandler;
import modi.backend.ingestion.infra.culture.CultureExhibitionClient;
import modi.backend.ingestion.properties.PublicDataProperties;
import modi.backend.support.error.CoreException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

/**
 * <b>운영 설정이 만든 RestClient가 XML을 실제로 바인딩하는지</b> 지키는 회귀 테스트 —
 * {@link KoreaCultureInformationClientConfig}의 빈 메서드를 직접 불러 검증한다.
 * <p>
 * <b>왜 이 테스트가 필요한가</b>: {@code RestClient.builder().configureMessageConverters(...)}를 <b>한 번이라도</b>
 * 호출하면 기본 컨버터 등록이 통째로 꺼진다. 그러면 XML 컨버터가 사라져
 * {@code .body(KoreaCultureDto.Realm2ListResponse.class)}가 정상 응답에서도 {@code UnknownContentTypeException}으로 죽는다.
 * 다른 테스트들은 자체 조립한 RestClient를 쓰므로 이 사고를 잡지 못한다 — <b>운영 조립을 직접 태우는 건 여기뿐이다.</b>
 * 누군가 "UTF-8 컨버터를 다시 넣자"고 config를 고치면 이 테스트가 먼저 깨진다.
 */
class CultureClientConverterWiringTest {

	private static final String REALM2_XML =
			"<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
					+ "<response><header><resultCode>00</resultCode><resultMsg>정상입니다.</resultMsg></header>"
					+ "<body><totalCount>1</totalCount><items>"
					+ "<item><seq>319005</seq><title>패트릭 블랑: 수직정원</title><place>부산현대미술관</place>"
					+ "<area>부산</area></item>"
					+ "</items></body></response>";

	private static final CatalogFetchCriteria CRITERIA =
			CatalogFetchCriteria.of(ExhibitionRealm.EXHIBITION, 100, 500);

	private MockWebServer server;
	private CultureExhibitionClient client;

	@BeforeEach
	void setUp() throws IOException {
		server = new MockWebServer();
		server.start();
		PublicDataProperties properties = new PublicDataProperties(
				"http://localhost:" + server.getPort(), "test-service-key", 15L);
		// 운영 설정 그대로 — 여기서 컨버터 배선이 틀어지면 아래 테스트가 깨진다.
		RestClient restClient = new KoreaCultureInformationClientConfig()
				.koreaCultureInformationClient(properties);
		client = new CultureExhibitionClient(restClient, new CultureApiErrorHandler(), properties);
	}

	@AfterEach
	void tearDown() throws IOException {
		server.shutdown();
	}

	@Test
	@DisplayName("운영 설정의 RestClient가 XML을 도메인 페이지로 바인딩한다(기본 컨버터 유지 확인)")
	void 운영조립_XML_바인딩() {
		// 원천과 동일하게 charset 없는 application/xml — 인코딩은 본문 XML 선언이 정한다.
		server.enqueue(new MockResponse().setBody(REALM2_XML).addHeader("Content-Type", "application/xml"));

		CatalogPage page = client.fetchPage(CRITERIA, 1);

		assertThat(page.items()).hasSize(1);
		// 한글이 살아남는지까지 본다 — charset 헤더가 없어도 XML 선언(UTF-8)으로 해석되어야 한다.
		assertThat(page.items().get(0).title()).isEqualTo("패트릭 블랑: 수직정원");
		assertThat(page.items().get(0).place()).isEqualTo("부산현대미술관");
	}

	@Test
	@DisplayName("XML이 아닌 200 응답(text/plain)도 도메인 예외로 변환된다 — 미처리 예외로 새지 않는다")
	void 비XML_200응답_도메인예외로_변환() {
		// 이 원천은 XML이 아닌 것을 돌려줄 수 있다(실측: 잘못된 인증키 → text/plain "Unauthorized").
		// 클래스 매핑은 Content-Type으로 컨버터를 고르므로 맞는 컨버터가 없어 UnknownContentTypeException이 난다.
		server.enqueue(new MockResponse().setBody("Unauthorized")
				.addHeader("Content-Type", "text/plain;charset=utf-8"));

		assertThatThrownBy(() -> client.fetchPage(CRITERIA, 1))
				.isInstanceOf(CoreException.class)
				.hasMessageContaining("외부 전시 API 호출 실패");
	}
}
