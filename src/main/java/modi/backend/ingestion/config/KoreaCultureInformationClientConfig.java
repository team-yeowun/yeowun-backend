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
     * <p>
     * [설계 및 설정 의도]
     * 1. 파싱 전략: 응답(XML)을 Spring의 XML 메시지 컨버터가 곧바로 {@code CultureApiResponse}로 역직렬화합니다
     *    ({@code .body(CultureApiResponse.class)}). 정상 응답 여부 판정은 역직렬화 이후
     *    {@link modi.backend.ingestion.infra.culture.CultureApiMapper#verify}가 맡습니다.
     * 2. 컨버터 등록: 기본 컨버터 목록을 건드리지 않습니다. {@code configureMessageConverters(...)}를 한 번이라도
     *    호출하면 기본 등록이 꺼져 XML 컨버터가 사라지고, 정상 응답조차 읽지 못합니다(UnknownContentTypeException).
     * 3. 인코딩: 원천이 Content-Type에 charset을 누락하지만 본문에 {@code <?xml encoding="UTF-8"?>} 선언이 있어
     *    XML 파서가 이를 보고 해석하므로 한글이 깨지지 않습니다(문자열 수신 시절의 UTF-8 강제는 불필요해졌습니다).
     * 4. 타임아웃: 스레드 고갈 장애 전파를 막기 위해 최초 연결(Connect)은 3초로 짧게 설정하여 Fail-Fast를 유도합니다.
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

        // 2. RestClient 조립 — 메시지 컨버터는 기본값을 그대로 쓴다(XML 컨버터 포함).
        //    configureMessageConverters(...)를 호출하는 순간 기본 등록이 꺼지므로, 커스터마이즈하려면
        //    registerDefaults()를 반드시 함께 불러야 한다. 지금은 손댈 이유가 없어 아예 부르지 않는다.
		return RestClient.builder()
				.baseUrl(properties.baseUrl())
				.requestFactory(requestFactory)
				.build();
	}
}
