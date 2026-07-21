package modi.backend.ingestion.infra.culture;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

import modi.backend.ingestion.config.KoreaCultureInformationClientConfig;
import modi.backend.ingestion.domain.ExhibitionRealm;
import modi.backend.ingestion.domain.data.CatalogExhibitionData;
import modi.backend.ingestion.domain.data.CatalogFetchCriteria;
import modi.backend.ingestion.domain.data.CatalogPage;
import modi.backend.ingestion.properties.PublicDataProperties;

/**
 * {@link CultureExhibitionClient} — <b>실제 공공데이터 전시 API를 호출하는 수동 확인용</b> 테스트.
 * <p>
 * <b>자동 실행 대상이 아니다.</b> {@code @Tag("manual")}이라 {@code ./gradlew test}(CI 포함)에서 제외된다.
 * 돌리는 방법은 둘 —
 * <ul>
 *   <li>{@code ./gradlew manualTest}</li>
 *   <li>IDE에서 이 클래스·메서드를 직접 실행</li>
 * </ul>
 * 인증키는 환경변수 {@code CULTURE_API_KEY}를 먼저 쓰고, 없으면 application.yaml의 기본값을 쓴다.
 * <p>
 * 스프링 컨텍스트를 띄우지 않고 {@link KoreaCultureInformationClientConfig}의 빈 메서드를 직접 불러 조립하므로
 * <b>운영과 같은 RestClient 설정</b>(타임아웃·메시지 컨버터)을 탄다 — 배선이 틀어지면 여기서 먼저 드러난다.
 * <p>
 * 응답 파싱·페이징 규칙의 세부 검증은 MockWebServer 기반 {@code CultureExhibitionClientTest}가 담당한다.
 * 여기서는 "실제 원천에 붙어서 끝까지 도는가"만 본다 — 호출 한도를 아끼려 1콜(5건)로 제한한다.
 */
@Tag("manual")
class CultureExhibitionClientTest {

	private static final String BASE_URL = "https://apis.data.go.kr/B553457/cultureinfo";
	/** application.yaml의 기본값과 동일 — 환경변수가 있으면 그쪽이 이긴다. */
	private static final String DEFAULT_KEY = "55aa921462823cce4b6aa1d36b0f5c318b0100a16af6fc56c89527e7353ad154";

	@Test
	@DisplayName("원천 응답 구조 — realm2가 실제로 내려주는 XML을 그대로 매핑한 형태를 찍는다")
	void 원천_응답구조_확인() {
		CatalogFetchCriteria criteria = CatalogFetchCriteria.of(ExhibitionRealm.EXHIBITION, 5, 5);

		// 어댑터의 단건 호출 = 원천 응답(CultureRealm2ListResponse) 그대로. 도메인 변환·벤더 스냅샷 분리 이전이다.
		CultureRealm2ListResponse response = client().fetchListPage(criteria, 1);

		printAsJson("realm2 원천 응답(1페이지)", response);

		assertThat(response.isSuccess()).isTrue();
		assertThat(response.body().totalCount()).isPositive();
		assertThat(response.items()).isNotEmpty();
	}

	@Test
	@DisplayName("fetchAll — 원천 응답을 적재 가능한 수집 데이터로 접어서 돌려준다")
	void fetchAll_실제호출() {
		CultureExhibitionClient reader = client();

		// 5건 상한 = 1콜(원천 전량을 받지 않는다 — 이 테스트의 관심사는 "접어서 돌려주는가"다).
		CatalogPage result = reader.fetchPage(CatalogFetchCriteria.of(ExhibitionRealm.EXHIBITION, 5, 5), 1);

		assertThat(result.items()).isNotEmpty();
		assertThat(result.items()).allMatch(CatalogExhibitionData::isPersistable);
		// 원천이 총 건수를 알려줬다 = 응답 본문이 온전히 파싱됐다.
		assertThat(result.totalCount()).isNotNull().isPositive();
	}

	/** 운영 설정(타임아웃·메시지 컨버터)을 그대로 태운다 — 배선이 틀어지면 여기서 먼저 드러난다. */
	private CultureExhibitionClient client() {
		String serviceKey = System.getenv("CULTURE_API_KEY");
		PublicDataProperties properties = new PublicDataProperties(BASE_URL,
				serviceKey == null || serviceKey.isBlank() ? DEFAULT_KEY : serviceKey, 15L);
		RestClient restClient = new KoreaCultureInformationClientConfig()
				.koreaCultureInformationClient(properties);
		// 감사 저장은 DB가 필요해 이 테스트의 관심사가 아니다(전송·수집만 본다).
		return new CultureExhibitionClient(restClient, new CultureApiErrorHandler(), properties);
	}

	/** 응답 구조를 눈으로 확인하려고 보기 좋은 JSON으로 찍는다. */
	private void printAsJson(String label, Object value) {
		ObjectMapper json = JsonMapper.builder()
				.disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
				.build();
		System.out.println("================ " + label + " ================");
		System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(value));
		System.out.println("=".repeat(32 + label.length()));
	}
}
