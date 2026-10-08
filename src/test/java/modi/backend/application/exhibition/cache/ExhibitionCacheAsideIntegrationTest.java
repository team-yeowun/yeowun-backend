package modi.backend.application.exhibition.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import modi.backend.TestcontainersConfiguration;
import modi.backend.application.admin.AdminExhibitionFacade;
import modi.backend.application.exhibition.ExhibitionCriteria;
import modi.backend.application.exhibition.ExhibitionFacade;
import modi.backend.application.exhibition.contract.ExhibitionRegistrar;
import modi.backend.application.exhibition.contract.ExhibitionRegistration;
import modi.backend.domain.exhibition.catalog.Exhibition;
import modi.backend.domain.exhibition.catalog.ExhibitionCategory;
import modi.backend.domain.exhibition.catalog.ExhibitionPlace;
import modi.backend.domain.exhibition.catalog.ExhibitionPlaceRepository;
import modi.backend.domain.exhibition.catalog.ExhibitionRegion;
import modi.backend.domain.exhibition.catalog.ExhibitionRepository;
import modi.backend.domain.exhibition.genre.GenreProvider;
import modi.backend.support.cache.MyCache;

/**
 * - 실제 Redis·트랜잭션 위에서 Cache-Aside가 설계대로 도는지 고정
 *   - 상세는 Redis에만 담기고 L1에는 생기지 않음
 *   - 관리자 수정 커밋 뒤 Redis의 목록·상세와 이 서버의 L1 목록이 지워짐
 *   - 수집 등록 커밋 뒤 Redis의 목록이 지워짐
 *
 * - 목 단위 테스트로는 AFTER_COMMIT 리스너가 실제로 붙는지까지는 못 봄 — 그래서 실물로 확인
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.exhibition.enrich.scheduling-enabled=false")
class ExhibitionCacheAsideIntegrationTest {

	@Autowired
	private ExhibitionFacade exhibitionFacade;
	@Autowired
	private AdminExhibitionFacade adminExhibitionFacade;
	@Autowired
	private ExhibitionRegistrar exhibitionRegistrar;
	@Autowired
	private ExhibitionRepository exhibitionRepository;
	@Autowired
	private ExhibitionPlaceRepository exhibitionPlaceRepository;
	@Autowired
	private StringRedisTemplate redis;
	@Autowired
	private CaffeineCacheManager localCacheManager;

	private static final ExhibitionCriteria.Search 탐색첫페이지 = new ExhibitionCriteria.Search(
			null, null, null, null, null, null, "latest", null, null, null, null, null);

	@BeforeEach
	void 캐시비우기() {
		for (MyCache cache : ExhibitionCache.ALL) {
			var keys = redis.keys("yeowun:" + cache.getName() + "::*");
			if (keys != null && !keys.isEmpty()) {
				redis.delete(keys);
			}
		}
		for (MyCache list : ExhibitionCache.LISTS) {
			localCacheManager.getCache(list.getName()).clear();
		}
	}

	private static String key(MyCache cache, Object entry) {
		return "yeowun:" + cache.getName() + "::" + entry;
	}

	private Long 진행중전시() {
		LocalDate today = LocalDate.now();
		ExhibitionPlace place = exhibitionPlaceRepository.resolveOrCreate("캐시IT전시장-" + UUID.randomUUID(),
				ExhibitionRegion.SEOUL, "종로구", 127.0, 37.5);
		Exhibition saved = exhibitionRepository.save(Exhibition.createCatalog("CACHE-IT-" + UUID.randomUUID(),
				"캐시 통합 전시", place.getId(), ExhibitionRegion.SEOUL, today.minusDays(1), today.plusDays(30),
				ExhibitionCategory.PAINTING, "poster", "detail", "기관"));
		return saved.getId();
	}

	@Test
	@DisplayName("상세는 Redis에만 담기고 L1에는 생기지 않는다")
	void 상세_Redis에만() {
		Long id = 진행중전시();

		exhibitionFacade.getDetail(new ExhibitionCriteria.Detail(id, null));

		assertThat(redis.hasKey(key(ExhibitionCache.ExhibitionDetail.INSTANCE, id))).isTrue();
		assertThat(localCacheManager.getCache(ExhibitionCache.ExhibitionDetail.INSTANCE.getName())).isNull();
	}

	@Test
	@DisplayName("관리자 수정 커밋 뒤 Redis의 목록과 이 서버의 L1 목록이 지워지고, 상세는 옛 값을 서빙하지 않는다")
	void 관리자수정_커밋뒤_삭제() {
		Long id = 진행중전시();
		exhibitionFacade.search(탐색첫페이지);
		exhibitionFacade.getDetail(new ExhibitionCriteria.Detail(id, null));
		String listKey = key(ExhibitionCache.ExploreLatestP1.INSTANCE, ExhibitionCache.ENTRY_KEY);
		assertThat(redis.hasKey(listKey)).isTrue();
		assertThat(localCacheManager.getCache("ExploreLatestP1").get(ExhibitionCache.ENTRY_KEY)).isNotNull();

		adminExhibitionFacade.editExhibition(id, "고친 제목", null, null, null);

		assertThat(redis.hasKey(listKey)).isFalse();
		assertThat(localCacheManager.getCache("ExploreLatestP1").get(ExhibitionCache.ENTRY_KEY)).isNull();
		// 상세는 "키가 없다"가 아니라 "옛 값을 서빙하지 않는다"로 본다. 스위트 전체가 Redis 컨테이너 하나를 나눠 써서
		// 키 유무는 다른 컨텍스트의 잔여 작업에 흔들릴 수 있지만, 삭제가 빠졌다면 아래 조회는 반드시 옛 제목을 돌려준다.
		String detailKey = key(ExhibitionCache.ExhibitionDetail.INSTANCE, id);
		assertThat(exhibitionFacade.getDetail(new ExhibitionCriteria.Detail(id, null)).title())
				.as("상세 캐시 ttl=%s", redis.getExpire(detailKey))
				.isEqualTo("고친 제목");
	}

	@Test
	@DisplayName("수집 등록 커밋 뒤 Redis의 목록이 지워지고, 다음 조회가 새 전시를 담아 다시 적재한다")
	void 수집등록_커밋뒤_목록삭제() {
		exhibitionFacade.search(탐색첫페이지);
		String listKey = key(ExhibitionCache.ExploreLatestP1.INSTANCE, ExhibitionCache.ENTRY_KEY);
		assertThat(redis.hasKey(listKey)).isTrue();

		LocalDate today = LocalDate.now();
		exhibitionRegistrar.register(new ExhibitionRegistration("CACHE-IT-REG-" + UUID.randomUUID(), "새로 들어온 전시",
				"캐시IT등록장소-" + UUID.randomUUID(), ExhibitionRegion.SEOUL, "종로구", 127.0, 37.5,
				today.minusDays(1), today.plusDays(30), ExhibitionCategory.PAINTING, "poster", "detail", "기관",
				"무료", "설명", "img", null, null, null, "회화", GenreProvider.MOCK, "mock"),
				LocalDateTime.now());

		assertThat(redis.hasKey(listKey)).isFalse();
	}
}
