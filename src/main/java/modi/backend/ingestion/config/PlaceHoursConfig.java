package modi.backend.ingestion.config;

import java.net.http.HttpClient;
import java.time.Duration;

import modi.backend.ingestion.properties.PlaceHoursProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import modi.backend.ingestion.domain.port.PlaceHoursProvider;
import modi.backend.ingestion.infra.google.GoogleMapsClient;
import modi.backend.ingestion.infra.mock.MockPlaceHoursProvider;

/**
 * 전시 영업시간(구글 Places New) 관련 빈 등록 — 장르(Gemini) 구성과 동형.
 * <p>
 * mock·google 두 조회기는 {@code @Component}로 항상 공존하고, 여기서 {@code app.exhibition.place-hours.provider}(+키 유무)에 따라
 * 주 조회기(@Primary)를 고른다. 주입 지점(enricher)은 선택된 하나만 본다(DIP). 기본은 mock이라 로컬·CI·develop은 유료호출 0.
 */
@Configuration
@EnableConfigurationProperties(PlaceHoursProperties.class)
public class PlaceHoursConfig {

	/** 연결 수립 상한(읽기 타임아웃과 별개) — 세 수집 클라이언트가 같은 값을 쓴다. */
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

	/**
	 * 구글 Places 전용 RestClient. baseUrl·읽기 타임아웃(워커 스레드 장기 점유·부팅 지연 방지)을 설정에서 주입한다.
	 * 요청선(경로·헤더·본문)은 {@link GoogleMapsClient}가 이 클라이언트로 직접 조립한다 —
	 * 선언형 HTTP Interface 프록시를 두지 않는다(공공데이터 전시 API와 같은 구조).
	 */
	@Bean
	public RestClient googleMapsRestClient(PlaceHoursProperties properties) {
		// 연결 수립 상한 — 살아있는 상대는 1초 안에 붙는다. 팩토리엔 세터가 없어 HttpClient에 걸어 넘긴다.
		HttpClient httpClient = HttpClient.newBuilder()
				.connectTimeout(CONNECT_TIMEOUT)
				.build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(Duration.ofSeconds(properties.timeoutSeconds()));
		return RestClient.builder()
				.baseUrl(properties.baseUrl())
				.requestFactory(requestFactory)
				.build();
	}

	/**
	 * 주 영업시간 조회기 선택. {@code provider=google}이고 키가 있으면 실호출기, 그 외(기본·키없음)면 mock을 @Primary로 노출한다.
	 * 두 구현 빈은 그대로 존재하므로(공존), 설정만 바꿔 무중단 교체할 수 있다.
	 */
	@Bean
	@Primary
	public PlaceHoursProvider placeHoursProvider(PlaceHoursProperties properties,
			MockPlaceHoursProvider mockPlaceHoursProvider, GoogleMapsClient googleMapsClient) {
		return properties.useGoogle() ? googleMapsClient : mockPlaceHoursProvider;
	}
}
