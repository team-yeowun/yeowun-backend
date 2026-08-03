package modi.backend.application.exhibition;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import modi.backend.TestcontainersConfiguration;
import modi.backend.domain.exhibition.catalog.ExhibitionCategory;
import modi.backend.domain.exhibition.catalog.ExhibitionPlaceRepository;
import modi.backend.domain.exhibition.catalog.ExhibitionRegion;
import modi.backend.domain.exhibition.catalog.ExhibitionRepository;
import modi.backend.domain.exhibition.catalog.ExhibitionTestFactory;
import modi.backend.ingestion.domain.port.ExhibitionCatalogClient;
import modi.backend.support.time.AppTime;

/**
 * STEP 2 회귀 가드 — <b>실제 부팅된 앱</b>(서블릿 컨테이너 + 전체 컨트롤러 등록)에서
 * {@code GET /api/v1/exhibitions/count}가 {@code GET /api/v1/exhibitions/{exhibitionId}}로 새지 않는지 본다.
 * 구현자는 @WebMvcTest 안에서만 확인했다(그 슬라이스에는 컨트롤러가 하나뿐이라 경합이 약하다).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "app.exhibition.enrich.scheduling-enabled=false")
class ExhibitionCountRoutingBootTest {

	private static final AtomicInteger SEQ = new AtomicInteger(1);

	@LocalServerPort
	int port;
	@Autowired
	ExhibitionRepository exhibitionRepository;
	@Autowired
	ExhibitionPlaceRepository exhibitionPlaceRepository;

	@MockitoBean
	ExhibitionCatalogClient exhibitionCatalogClient;

	@Test
	@DisplayName("실제 부팅 앱에서 GET /api/v1/exhibitions/count 는 상세 조회로 빠지지 않고 count 응답을 준다")
	void 실제_부팅앱에서_count_라우팅이_상세로_새지_않는다() {
		LocalDate today = LocalDate.now(AppTime.KST);
		String token = "라우팅토큰" + SEQ.getAndIncrement();
		Long placeId = ExhibitionTestFactory.placeId(exhibitionPlaceRepository, "라우팅검증관", ExhibitionRegion.SEOUL);
		for (int i = 0; i < 4; i++) {
			exhibitionRepository.save(ExhibitionTestFactory.catalog(placeId, "groute-" + SEQ.getAndIncrement(),
					token + " 전시 " + i, today.minusDays(i), today.plusDays(30), ExhibitionCategory.PAINTING));
		}

		HttpResponse<String> res = get("/api/v1/exhibitions/count?keyword=" + enc(token));

		System.out.println("=== BOOTED APP: GET /api/v1/exhibitions/count ===");
		System.out.println("status=" + res.statusCode());
		System.out.println("body=" + res.body());

		assertThat(res.statusCode()).as("상세로 빠지면 400(경로변수 타입 불일치) 또는 404가 난다").isEqualTo(200);
		assertThat(res.body()).contains("\"count\":4").contains("\"exact\":true");
		assertThat(res.body()).as("상세 응답이면 exhibitionId/title 같은 필드가 온다").doesNotContain("\"exhibitionId\"");
	}

	@Test
	@DisplayName("실제 부팅 앱에서 목록 응답 JSON에 totalCount 필드가 아예 없다")
	void 실제_부팅앱_목록응답에_totalCount가_없다() {
		LocalDate today = LocalDate.now(AppTime.KST);
		String token = "라우팅목록토큰" + SEQ.getAndIncrement();
		Long placeId = ExhibitionTestFactory.placeId(exhibitionPlaceRepository, "라우팅목록관", ExhibitionRegion.BUSAN);
		for (int i = 0; i < 3; i++) {
			exhibitionRepository.save(ExhibitionTestFactory.catalog(placeId, "grlist-" + SEQ.getAndIncrement(),
					token + " 전시 " + i, today.minusDays(i), today.plusDays(30), ExhibitionCategory.PAINTING));
		}

		String mid = get("/api/v1/exhibitions?keyword=" + enc(token) + "&size=2").body();
		String last = get("/api/v1/exhibitions?keyword=" + enc(token) + "&size=50").body();

		System.out.println("=== BOOTED APP: GET /api/v1/exhibitions (mid) ===");
		System.out.println(mid);
		System.out.println("=== BOOTED APP: GET /api/v1/exhibitions (last page) ===");
		System.out.println(last.substring(0, Math.min(400, last.length())));

		assertThat(mid).doesNotContain("totalCount");
		assertThat(last).doesNotContain("totalCount");
		assertThat(mid).as("nextCursor는 살아 있어야 한다").contains("\"nextCursor\"");
		assertThat(last).as("마지막 페이지에서도 nextCursor 필드는 null로 남아야 한다")
				.contains("\"nextCursor\":null");
	}

	@Test
	@DisplayName("실제 부팅 앱에서 /count 와 목록 전량이 같은 수다 (HTTP 계층 끝단)")
	void 부팅앱에서_count와_목록전량이_같다() {
		LocalDate today = LocalDate.now(AppTime.KST);
		String token = "라우팅대조토큰" + SEQ.getAndIncrement();
		Long seoul = ExhibitionTestFactory.placeId(exhibitionPlaceRepository, "대조서울관", ExhibitionRegion.SEOUL);
		Long busan = ExhibitionTestFactory.placeId(exhibitionPlaceRepository, "대조부산관", ExhibitionRegion.BUSAN);
		for (int i = 0; i < 7; i++) {
			exhibitionRepository.save(ExhibitionTestFactory.catalog(i % 2 == 0 ? seoul : busan,
					i % 2 == 0 ? ExhibitionRegion.SEOUL : ExhibitionRegion.BUSAN,
					"grcmp-" + SEQ.getAndIncrement(), token + " 전시 " + i,
					today.minusDays(i), today.plusDays(30), ExhibitionCategory.PAINTING));
		}

		String countBody = get("/api/v1/exhibitions/count?keyword=" + enc(token) + "&region=SEOUL").body();
		int listed = 목록_전량_센다(token, "SEOUL");

		System.out.println("=== BOOTED APP parity: count=" + countBody + " list=" + listed);
		// 0 == 0으로 통과하는 공허한 대조를 막는다 — 서울 4건이 실제로 잡혀야 한다(7건 중 짝수 인덱스).
		assertThat(listed).isEqualTo(4);
		assertThat(countBody).contains("\"count\":" + listed);
	}

	private int 목록_전량_센다(String token, String region) {
		int total = 0;
		String cursor = null;
		for (int page = 0; page < 50; page++) {
			String url = "/api/v1/exhibitions?keyword=" + enc(token) + "&region=" + region + "&size=2"
					+ (cursor == null ? "" : "&cursor=" + enc(cursor));
			String body = get(url).body();
			total += countOccurrences(body, "\"exhibitionId\"");
			if (!body.contains("\"hasNext\":true")) {
				break;
			}
			cursor = between(body, "\"nextCursor\":\"", "\"");
		}
		return total;
	}

	private HttpResponse<String> get(String path) {
		try {
			return HttpClient.newHttpClient().send(
					HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
					HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private static String enc(String v) {
		return URLEncoder.encode(v, StandardCharsets.UTF_8);
	}

	private static int countOccurrences(String haystack, String needle) {
		int n = 0;
		int i = haystack.indexOf(needle);
		while (i >= 0) {
			n++;
			i = haystack.indexOf(needle, i + needle.length());
		}
		return n;
	}

	private static String between(String s, String start, String end) {
		int a = s.indexOf(start);
		if (a < 0) {
			return null;
		}
		a += start.length();
		int b = s.indexOf(end, a);
		return b < 0 ? null : s.substring(a, b);
	}
}
