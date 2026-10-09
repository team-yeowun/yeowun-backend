package modi.backend.interfaces.exhibition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import modi.backend.TestcontainersConfiguration;
import modi.backend.application.exhibition.cache.ExhibitionCache;
import modi.backend.domain.exhibition.catalog.Exhibition;
import modi.backend.domain.exhibition.catalog.ExhibitionCategory;
import modi.backend.domain.exhibition.catalog.ExhibitionPlace;
import modi.backend.domain.exhibition.catalog.ExhibitionPlaceRepository;
import modi.backend.domain.exhibition.catalog.ExhibitionRegion;
import modi.backend.domain.exhibition.catalog.ExhibitionRepository;
import modi.backend.support.cache.CacheLookupMetrics;
import modi.backend.support.cache.CacheManager;
import modi.backend.support.cache.MyCache;

/**
 * - 프론트가 실제로 보내는 목록 요청이 캐시를 타고, 그래도 응답은 캐시 없이 읽은 것과 같은지 실물(HTTP → DB·Redis)로 고정
 *   - 탐색 첫 화면: {@code ?keyword=&sort=latest&size=20} — 빈 검색어가 캐시를 막지 않아야 함
 *   - 홈 섹션: {@code ?section=free&size=2} 등 — 기본 크기 페이지에서 잘라 줌
 *
 * - "같은 응답"의 기준은 캐시를 끈({@code app.cache.enabled=false}) 같은 요청
 *   - 캐시를 끄면 컨트롤러는 자르지 않고 요청 크기 그대로 DB에서 읽는다 — 바뀌기 전과 같은 경로
 *   - 항목·순서·총 건수·hasNext·nextCursor가 같아야 하고, 그 nextCursor로 이어 읽은 다음 페이지도 맞아야 함
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.exhibition.enrich.scheduling-enabled=false")
@AutoConfigureMockMvc
class ExhibitionListCacheHitIntegrationTest {

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private ObjectMapper objectMapper;
	@Autowired
	private CacheManager cacheManager;
	@Autowired
	private CacheLookupMetrics lookupMetrics;
	@Autowired
	private CaffeineCacheManager localCacheManager;
	@Autowired
	private StringRedisTemplate redis;
	@Autowired
	private ExhibitionRepository exhibitionRepository;
	@Autowired
	private ExhibitionPlaceRepository exhibitionPlaceRepository;
	@Autowired
	private PlatformTransactionManager transactionManager;

	/**
	 * - 세 홈 섹션(곧 종료·무료·이번 달 신규)과 탐색 첫 페이지에 동시에 걸리는 진행 중 전시 25건
	 *   - 어느 크기로 잘라도 다음 페이지가 있도록 기본 크기(20)보다 많이 둠
	 *   - 인기순 커서(조회수)도 실제로 갈리도록 조회수를 서로 다르게 줌(일부는 같게 두어 id 동점 처리도 탐)
	 */
	@BeforeEach
	void 준비() {
		ReflectionTestUtils.setField(cacheManager, "enabled", true);
		LocalDate today = LocalDate.now();
		ExhibitionPlace place = exhibitionPlaceRepository.resolveOrCreate("목록캐시IT-" + UUID.randomUUID(),
				ExhibitionRegion.SEOUL, "종로구", 127.0, 37.5);
		Map<Long, Long> views = new HashMap<>();
		for (int i = 0; i < 25; i++) {
			Exhibition e = Exhibition.createCatalog("LIST-CACHE-IT-" + UUID.randomUUID(), "목록 캐시 전시 " + i,
					place.getId(), ExhibitionRegion.SEOUL, today, today.plusDays(1 + i % 5),
					ExhibitionCategory.PAINTING, "poster", "detail", "기관");
			e.applyPriceJudgement("무료");
			Exhibition saved = exhibitionRepository.save(e);
			views.put(saved.getId(), 100_000L + (i % 7) * 10);
		}
		new TransactionTemplate(transactionManager).executeWithoutResult(
				status -> exhibitionRepository.increaseViewCounts(views));
		비우기();
	}

	private void 비우기() {
		for (MyCache list : ExhibitionCache.LISTS) {
			var keys = redis.keys("yeowun:" + list.getName() + "::*");
			if (keys != null && !keys.isEmpty()) {
				redis.delete(keys);
			}
			localCacheManager.getCache(list.getName()).clear();
		}
	}

	private JsonNode data(String query) throws Exception {
		String body = mockMvc.perform(get("/api/v1/exhibitions?" + query))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return objectMapper.readTree(body).get("data");
	}

	private JsonNode 캐시없이(String query) throws Exception {
		ReflectionTestUtils.setField(cacheManager, "enabled", false);
		try {
			return data(query);
		} finally {
			ReflectionTestUtils.setField(cacheManager, "enabled", true);
		}
	}

	private static List<Long> ids(JsonNode data) {
		return StreamSupport.stream(data.get("content").spliterator(), false)
				.map(item -> item.get("exhibitionId").asLong())
				.toList();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"section=ending-soon&size=2", "section=ending-soon&size=5",
			"section=free&size=2", "section=free&size=5",
			"section=opening-this-month&size=2", "section=opening-this-month&size=5",
			"keyword=&sort=popular&size=5", "keyword=&sort=ending&size=3"})
	@DisplayName("작은 첫 페이지는 캐시 없이 읽은 응답과 같고, 그 nextCursor로 이어 읽은 다음 페이지도 맞다")
	void 잘라준응답_캐시없는응답과같다(String query) throws Exception {
		JsonNode uncached = 캐시없이(query);

		JsonNode fill = data(query);    // 기본 크기 페이지를 캐시에 채우고 잘라 줌
		JsonNode hit = data(query);     // 캐시 히트에서 잘라 줌

		assertThat(fill).isEqualTo(uncached);
		assertThat(hit).isEqualTo(uncached);
		assertThat(uncached.get("hasNext").asBoolean()).as("데이터 25건이라 다음 페이지가 있어야 함").isTrue();

		// 다음 페이지 = 기본 크기 첫 페이지에서 그 다음 구간
		int size = Integer.parseInt(query.substring(query.lastIndexOf('=') + 1));
		List<Long> full = ids(캐시없이(query.substring(0, query.lastIndexOf("size=")) + "size=20"));
		JsonNode next = data(query + "&cursor=" + hit.get("nextCursor").asString());
		assertThat(ids(next)).isEqualTo(full.subList(size, Math.min(2 * size, full.size())));
	}

	@Test
	@DisplayName("탐색 첫 화면 요청(?keyword=&sort=latest&size=20)이 두 번째부터 캐시에서 나간다")
	void 탐색첫화면_빈검색어도_캐시히트() throws Exception {
		Seen before = seen(ExhibitionCache.ExploreLatestP1.INSTANCE);

		JsonNode first = data("keyword=&sort=latest&size=20");
		JsonNode second = data("keyword=&sort=latest&size=20");

		Seen after = seen(ExhibitionCache.ExploreLatestP1.INSTANCE);
		assertThat(after.misses() - before.misses()).as("첫 요청은 미스로 채움").isEqualTo(1);
		assertThat(after.hits() - before.hits()).as("두 번째는 히트").isEqualTo(1);
		assertThat(second).isEqualTo(first);
		assertThat(first).isEqualTo(캐시없이("sort=latest&size=20"));
	}

	@Test
	@DisplayName("홈 섹션 요청(?section=free&size=2)이 기본 크기 캐시 페이지를 타고 두 번째부터 히트한다")
	void 홈섹션_작은크기도_캐시히트() throws Exception {
		Seen before = seen(ExhibitionCache.HomeFree.INSTANCE);

		data("section=free&size=2");
		data("section=free&size=2");

		Seen after = seen(ExhibitionCache.HomeFree.INSTANCE);
		assertThat(after.misses() - before.misses()).as("첫 요청은 기본 크기 페이지를 미스로 채움").isEqualTo(1);
		assertThat(after.hits() - before.hits()).as("두 번째는 히트").isEqualTo(1);
	}

	@Test
	@DisplayName("1글자 검색어는 여전히 400이다 — 빈 값만 '없음'으로 바뀐다")
	void 한글자검색어_400() throws Exception {
		mockMvc.perform(get("/api/v1/exhibitions?keyword=a&sort=latest&size=20"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.meta.errorCode").value("INVALID_INPUT"));
	}

	@Test
	@DisplayName("region=·category=도 보내지 않은 것과 같다(같은 응답, 같은 캐시)")
	void 빈지역카테고리_없음과같다() throws Exception {
		Seen before = seen(ExhibitionCache.ExploreLatestP1.INSTANCE);

		JsonNode blank = data("keyword=&region=&category=&sort=latest&size=20");
		JsonNode absent = data("sort=latest&size=20");

		assertThat(blank).isEqualTo(absent);
		assertThat(seen(ExhibitionCache.ExploreLatestP1.INSTANCE).hits() - before.hits())
				.as("빈 값 요청이 채운 캐시를 없는 요청이 그대로 탐").isEqualTo(1);
	}

	/** 스냅샷은 살아 있는 누계라, 비교하려면 그 시점 값을 떠 둔다. */
	private Seen seen(MyCache cache) {
		CacheLookupMetrics.Counts c = lookupMetrics.snapshot(cache);
		return new Seen(c.l1Hits() + c.l2Hits(), c.misses());
	}

	private record Seen(long hits, long misses) {
	}
}
