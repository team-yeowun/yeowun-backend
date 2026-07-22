package modi.backend.ingestion.infra.google;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.DayOfWeek;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import modi.backend.ingestion.domain.data.PlaceHoursResult;
import modi.backend.ingestion.properties.GooglePlaceProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * {@link GooglePlaceClient} 실HTTP 계약 검증(MockWebServer) — 선언형 HTTP Interface 프록시를 걷어내고
 * {@link RestClient}로 직접 요청선을 조립하도록 바꾸면서, 그 <b>전송 계약</b>(경로·헤더·본문)을 여기서 못박는다.
 * 프록시 시절엔 어노테이션이 대신 보증하던 것들이라 검증이 없었다.
 */
class GooglePlaceClientTest {

	private MockWebServer server;
	private GooglePlaceClient provider;

	@BeforeEach
	void setUp() throws IOException {
		server = new MockWebServer();
		server.start();
		GooglePlaceProperties properties = new GooglePlaceProperties("google", server.url("/").toString(),
				"test-api-key", "ko", "KR", 5L, 30, 100);
		// 운영 조립(GooglePlaceConfig)과 동일하게 JDK 팩토리 고정 — 테스트 클래스패스의 Apache HttpClient5가
		// 자동감지되면 전송 계층이 한 번 더 재시도해 요청 수 검증이 흔들린다(Gemini 테스트와 같은 이유).
		RestClient restClient = RestClient.builder().baseUrl(properties.baseUrl())
				.requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory())
				.build();
		provider = new GooglePlaceClient(restClient, properties);
	}

	@AfterEach
	void tearDown() throws IOException {
		server.shutdown();
	}

	private static MockResponse json(String body) {
		return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
	}

	@Test
	@DisplayName("Text Search를 POST /v1/places:searchText로 보내고 키·FieldMask를 헤더로 싣는다")
	void fetch_sendsPostWithApiKeyAndFieldMaskHeaders() throws InterruptedException {
		server.enqueue(json("""
				{"places":[{"id":"places/abc","displayName":{"text":"부산현대미술관"},
				"formattedAddress":"부산 사하구","regularOpeningHours":{"periods":[
				{"open":{"day":2,"hour":10,"minute":0},"close":{"day":2,"hour":18,"minute":0}}]}}]}"""));

		Optional<PlaceHoursResult> fetched = provider.fetch("부산현대미술관", "부산 사하구 낙동남로 1191");

		assertThat(fetched).isPresent();
		assertThat(fetched.get().data().weeklyHours().byDay()).containsOnlyKeys(DayOfWeek.TUESDAY);

		RecordedRequest recorded = server.takeRequest();
		assertThat(recorded.getMethod()).isEqualTo("POST");
		assertThat(recorded.getPath()).isEqualTo("/v1/places:searchText");
		// 키는 URL이 아니라 헤더로 — 쿼리스트링에 실리면 접근 로그·리퍼러에 남는다.
		assertThat(recorded.getHeader("X-Goog-Api-Key")).isEqualTo("test-api-key");
		// FieldMask는 New API 필수 — 빠지면 400이다.
		assertThat(recorded.getHeader("X-Goog-FieldMask"))
				.isEqualTo("places.id,places.displayName,places.formattedAddress,places.regularOpeningHours");
		assertThat(recorded.getRequestUrl().querySize()).isZero();
	}

	@Test
	@DisplayName("장소명이 있으면 주소와 함께 질의하고, 언어·지역 코드를 본문에 싣는다")
	void fetch_buildsQueryFromNameAndAddress() throws InterruptedException {
		server.enqueue(json("{\"places\":[]}"));

		provider.fetch("부산현대미술관", "부산 사하구 낙동남로 1191");

		String body = server.takeRequest().getBody().readUtf8();
		assertThat(body).contains("부산현대미술관 부산 사하구 낙동남로 1191")
				.contains("\"languageCode\":\"ko\"").contains("\"regionCode\":\"KR\"");
	}

	@Test
	@DisplayName("장소명이 없으면 주소만으로 질의한다")
	void fetch_fallsBackToAddressOnlyQuery() throws InterruptedException {
		server.enqueue(json("{\"places\":[]}"));

		provider.fetch("  ", "부산 사하구 낙동남로 1191");

		assertThat(server.takeRequest().getBody().readUtf8()).contains("\"textQuery\":\"부산 사하구 낙동남로 1191\"");
	}

	@Test
	@DisplayName("검색 결과가 없으면 empty — 미발견은 실패가 아니다")
	void fetch_emptyWhenNoPlaces() {
		server.enqueue(json("{\"places\":[]}"));

		assertThat(provider.fetch("없는곳", "없는주소")).isEmpty();
	}

	@Test
	@DisplayName("전송 오류(5xx)는 감싸지 않고 전파한다 — 호출부가 스킵·재시도를 판단한다")
	void fetch_propagatesTransportError() {
		server.enqueue(new MockResponse().setResponseCode(500));

		assertThatThrownBy(() -> provider.fetch("부산현대미술관", "부산 사하구"))
				.isInstanceOf(RuntimeException.class);
	}
}
