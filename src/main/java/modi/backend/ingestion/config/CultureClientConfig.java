package modi.backend.ingestion.config;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import modi.backend.ingestion.properties.PublicDataProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import modi.backend.ingestion.infra.culture.CultureApi;
import modi.backend.ingestion.infra.culture.CultureExhibitionClient;

/**
 * 공공데이터 전시 API(한눈에보는문화정보) 클라이언트 배선 — 수집 전용이라 ingestion 슬라이스가 소유한다(ADR-12,
 * 코어 HttpClientConfig에는 OAuth 클라이언트만 남는다). RestClient 기반 HTTP Interface(ADR-09).
 */
@Configuration
@EnableConfigurationProperties(PublicDataProperties.class)
public class CultureClientConfig {

	/**
	 * 공공데이터 전시 API 전용 RestClient.
	 * 응답이 XML이라 JSON 컨버터는 쓰지 않는다 — 문자열로 받아 {@link modi.backend.ingestion.infra.culture.CultureApiMapper}가 XmlMapper로 직접 파싱한다.
	 */
	@Bean
	public RestClient cultureRestClient(PublicDataProperties properties) {
        // url
        String baseURL = properties.baseUrl();
        // HTTP 클라이언트 설정(JDK, 커넥트 타임아웃, 리드 타임아웃)
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(Duration.ofSeconds(properties.timeoutSeconds()));

        // MessageConverter
        // 1. 외부 API 응답 헤더(Content-Type)에 charset이 누락될 경우를 대비한 설정입니다.
        // 2. Spring 기본 String 컨버터(ISO-8859-1)가 작동하여 한글이 깨지는 현상을 방지합니다.
        // 3. 명시된 인코딩 정보가 없더라도 항상 UTF-8로 해석하도록 기본값을 강제합니다.
        StringHttpMessageConverter messageConverter = new StringHttpMessageConverter(StandardCharsets.UTF_8);

        // 조립
		return RestClient.builder()
				.baseUrl(baseURL)
				.requestFactory(requestFactory)
				.configureMessageConverters(builder -> builder
						.withStringConverter(messageConverter))
				.build();
	}

	/**
	 * {@link CultureExhibitionClient}가 사용하는 realm2/detail2 선언형 클라이언트.
	 * {@link #cultureRestClient} 위에 프록시를 세워 baseUrl·타임아웃 설정을 그대로 물려받는다.
	 */
	@Bean
	public CultureApi cultureApi(RestClient cultureRestClient) {
		return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(cultureRestClient)).build()
				.createClient(CultureApi.class);
	}
}
