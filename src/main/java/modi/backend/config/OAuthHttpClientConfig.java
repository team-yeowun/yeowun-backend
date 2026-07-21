package modi.backend.config;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import modi.backend.infra.auth.KakaoApi;
import modi.backend.infra.auth.NaverApi;

/**
 * RestClient 기반 HTTP Interface 클라이언트 등록. (선언형 REST 엔드포인트 관리 — ADR-09)
 * 수집(문화포털) 클라이언트 배선은 ingestion 슬라이스 소유({@code ingestion/config/KoreaCultureInformationClientConfig}) — ADR-12.
 */
@Configuration
public class OAuthHttpClientConfig {

	/**
	 * OAuth 토큰 교환·userinfo는 수 초면 끝난다 — 무응답 게이트웨이에 로그인 요청 스레드가 영구히 물리지 않게
	 * 읽기 타임아웃 상한을 건다(과거 WebClient 구성엔 이 상한이 없어 무한 대기가 가능했다).
	 */
	private static final Duration OAUTH_READ_TIMEOUT = Duration.ofSeconds(10);

	/**
	 * 연결 수립 상한 — 살아있는 상대는 1초 안에 붙고 죽은 상대는 계속 안 붙으므로 짧게 잡아 fail-fast 시킨다.
	 * {@link JdkClientHttpRequestFactory}엔 세터가 없다 — JDK에선 연결 수립이 요청별이 아니라 클라이언트 수준
	 * 설정이라 {@link java.net.http.HttpClient.Builder}에 걸어 팩토리에 넘겨야 한다.
	 */
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

	@Bean
	public KakaoApi kakaoApi() {
		return createClient(KakaoApi.class);
	}

	@Bean
	public NaverApi naverApi() {
		return createClient(NaverApi.class);
	}

	private <T> T createClient(Class<T> apiType) {
		HttpClient httpClient = HttpClient.newBuilder()
				.connectTimeout(CONNECT_TIMEOUT)
				.build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(OAUTH_READ_TIMEOUT);
		RestClient restClient = RestClient.builder().requestFactory(requestFactory).build();
		HttpServiceProxyFactory factory = HttpServiceProxyFactory
				.builderFor(RestClientAdapter.create(restClient))
				.build();
		return factory.createClient(apiType);
	}
}
