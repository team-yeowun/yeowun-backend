package modi.backend.ingestion.config;

import java.net.http.HttpClient;
import java.time.Duration;

import modi.backend.ingestion.infra.google.GooglePlaceClient;
import modi.backend.ingestion.properties.GooglePlaceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import modi.backend.ingestion.domain.port.PlaceHoursProvider;
import modi.backend.ingestion.infra.mock.MockPlaceHoursProvider;

/**
 * 구글 Places(New) 연결 빈 등록 — 벤더에 붙는 일(baseUrl·타임아웃)만 한다({@link AiModelConfig}와 같은 방침).
 * <p>
 * 여기에 <b>주 조회기 선택</b>이 함께 있는 이유: 선택 조건이 {@code provider=google}<b>이면서 키가 채워져 있을 것</b>이라
 * {@code @ConditionalOnProperty}(단일 프로퍼티 비교)로 표현되지 않는다. 키가 비면 조용히 mock으로 떨어뜨려 유료 호출
 * 전량 실패를 막는 안전장치라 조건을 단순화할 수도 없다 — 커스텀 {@code Condition}을 새로 쓰는 것보다 여기 삼항식이 읽힌다.
 * (장르 분류는 조건이 단순 문자열 비교라 어댑터의 {@code @ConditionalOnProperty}로 옮겼고, config가 없다.)
 * <p>
 * mock·google 두 조회기는 {@code @Component}로 항상 공존한다. 주입 지점(enricher)은 선택된 하나만 본다(DIP).
 * 기본은 mock이라 로컬·CI·develop은 유료호출 0.
 */
@Configuration
@EnableConfigurationProperties(GooglePlaceProperties.class)
public class GooglePlaceConfig {

	/** 연결 수립 상한(읽기 타임아웃과 별개) — 세 수집 클라이언트가 같은 값을 쓴다. */
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

	/**
	 * 구글 Places 전용 RestClient. baseUrl·읽기 타임아웃(워커 스레드 장기 점유·부팅 지연 방지)을 설정에서 주입한다.
	 * 요청선(경로·헤더·본문)은 {@link GooglePlaceClient}가 이 클라이언트로 직접 조립한다 —
	 * 선언형 HTTP Interface 프록시를 두지 않는다(공공데이터 전시 API와 같은 구조).
	 */
	@Bean
	public RestClient googleMapsRestClient(GooglePlaceProperties properties) {
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
	public PlaceHoursProvider placeHoursProvider(GooglePlaceProperties properties,
                                                 MockPlaceHoursProvider mockPlaceHoursProvider, GooglePlaceClient googlePlaceClient) {
		return properties.useGoogle() ? googlePlaceClient : mockPlaceHoursProvider;
	}
}
