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
 * 공공데이터 전시 API(한눈에보는문화정보) 클라이언트 설정(Configuration).
 * <p>
 * 수집(Ingestion) 전용 클라이언트이므로 ingestion 슬라이스에서 소유 및 관리합니다.
 * Spring 6.1의 RestClient를 기반으로 하는 선언적 HTTP Interface 스펙을 따릅니다.
 * </p>
 */
@Configuration
@EnableConfigurationProperties(PublicDataProperties.class)
public class KoreaCultureInformationClientConfig {
    /**
     * 공공데이터 전시 API 통신용 RestClient 빈을 생성합니다.
     * <p>
     * [설계 및 설정 의도]
     * 1. 파싱 전략: 공공데이터의 불규칙한 XML 응답을 처리하기 위해 스프링 컨버터를 쓰지 않고 순수 문자열(String)로 받습니다.
     *    이후 {@link modi.backend.ingestion.infra.culture.CultureApiMapper}가 XmlMapper를 이용해 직접 파싱하여 디버깅 안정성을 확보합니다.
     * 2. 인코딩 강제 설정: 원천 시스템이 응답 헤더(Content-Type)에 charset을 누락할 경우 Spring 기본 컨버터(ISO-8859-1)가 개입하여 한글이 깨집니다.
     *    이를 방지하고자 응답 헤더와 무관하게 항상 UTF-8로 해석하도록 기본값을 덮어씌웁니다.
     * 3. 타임아웃: 스레드 고갈 장애 전파를 막기 위해 최초 연결(Connect)은 3초로 짧게 설정하여 Fail-Fast를 유도합니다.
     * </p>
     *
     * @param properties 공공데이터 base URL 및 Read Timeout 값 설정 프로퍼티
     * @return 타임아웃 및 UTF-8 인코딩 룰이 적용된 RestClient
     */
	@Bean
	public RestClient koreaCultureInformationClient(PublicDataProperties properties) {
        // 1. HTTP 클라이언트 생성 및 타임아웃(Connect / Read) 설정
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();

		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(Duration.ofSeconds(properties.timeoutSeconds()));

        // 2. 한글 깨짐 방지용 UTF-8 전용 메시지 컨버터
        StringHttpMessageConverter messageConverter = new StringHttpMessageConverter(StandardCharsets.UTF_8);

        // 3. RestClient 조립
		return RestClient.builder()
				.baseUrl(properties.baseUrl())
				.requestFactory(requestFactory)
				.configureMessageConverters(builder -> builder.withStringConverter(messageConverter))
				.build();
	}

    /**
     * 선언형 HTTP Interface({@link CultureApi})의 프록시 구현체를 생성합니다.
     */
	@Bean
	public CultureApi cultureApi(RestClient koreaCultureInformationClient) {
		return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(koreaCultureInformationClient)).build()
				.createClient(CultureApi.class);
	}
}
