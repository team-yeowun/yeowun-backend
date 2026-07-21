package modi.backend.ingestion.config;

import java.time.Duration;

import modi.backend.ingestion.properties.PlaceHoursProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import modi.backend.ingestion.domain.port.PlaceHoursProvider;
import modi.backend.ingestion.infra.google.GoogleMapsApi;
import modi.backend.ingestion.infra.google.GooglePlaceHoursProvider;
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

	/** 구글 Places 전용 RestClient. baseUrl·읽기 타임아웃(워커 스레드 장기 점유·부팅 지연 방지)을 설정에서 주입한다. */
	@Bean
	public RestClient googleMapsRestClient(PlaceHoursProperties properties) {
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
		requestFactory.setReadTimeout(Duration.ofSeconds(properties.timeoutSeconds()));
		return RestClient.builder()
				.baseUrl(properties.baseUrl())
				.requestFactory(requestFactory)
				.build();
	}

	/** {@link GoogleMapsApi} 선언형 클라이언트 — {@link #googleMapsRestClient} 위에 HTTP Interface 프록시를 세운다. */
	@Bean
	public GoogleMapsApi googleMapsApi(RestClient googleMapsRestClient) {
		return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(googleMapsRestClient)).build()
				.createClient(GoogleMapsApi.class);
	}

	/**
	 * 주 영업시간 조회기 선택. {@code provider=google}이고 키가 있으면 실호출기, 그 외(기본·키없음)면 mock을 @Primary로 노출한다.
	 * 두 구현 빈은 그대로 존재하므로(공존), 설정만 바꿔 무중단 교체할 수 있다.
	 */
	@Bean
	@Primary
	public PlaceHoursProvider placeHoursProvider(PlaceHoursProperties properties,
			MockPlaceHoursProvider mockPlaceHoursProvider, GooglePlaceHoursProvider googlePlaceHoursProvider) {
		return properties.useGoogle() ? googlePlaceHoursProvider : mockPlaceHoursProvider;
	}
}
