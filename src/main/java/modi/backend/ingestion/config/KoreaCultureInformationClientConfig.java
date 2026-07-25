package modi.backend.ingestion.config;

import java.net.http.HttpClient;
import java.time.Duration;

import modi.backend.ingestion.properties.PublicDataProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import modi.backend.ingestion.infra.culture.CultureExhibitionClient;

/**
 * 공공데이터 전시 API(한눈에보는문화정보) 클라이언트 설정(Configuration).
 * <p>
 * 수집(Ingestion) 전용 클라이언트이므로 ingestion 슬라이스에서 소유 및 관리합니다.
 * 호출은 {@link CultureExhibitionClient}가 이 RestClient로 직접 수행합니다.
 * </p>
 */
@Configuration
@EnableConfigurationProperties(PublicDataProperties.class)
public class KoreaCultureInformationClientConfig {
    /**
     * 공공데이터 전시 API 통신용 RestClient 빈을 생성합니다.
     */
	@Bean
	public RestClient koreaCultureInformationClient(PublicDataProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();

		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(Duration.ofSeconds(properties.timeoutSeconds()));

		return RestClient.builder()
				.baseUrl(properties.baseUrl())
				.requestFactory(requestFactory)
				.build();
	}
}
