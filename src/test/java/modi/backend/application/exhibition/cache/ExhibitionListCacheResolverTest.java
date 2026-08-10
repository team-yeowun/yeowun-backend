package modi.backend.application.exhibition.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import modi.backend.application.exhibition.ExhibitionCriteria;

/**
 * - 어떤 요청이 어떤 캐시를 타는지 고정
 *   - 판정이 틀리면 캐시가 다른 조건의 목록을 서빙
 *   - "캐시를 안 태우는" 실수보다 "엉뚱하게 태우는" 실수가 훨씬 비쌈
 */
class ExhibitionListCacheResolverTest {

	private static ExhibitionCriteria.Search search(String section, String sort, String cursor, String region) {
		return new ExhibitionCriteria.Search(null, section, null, region, null, null, sort, null, null, cursor,
				null, null);
	}

	@Test
	@DisplayName("필터 없는 최신순 1페이지는 탐색 캐시를 탄다")
	void resolve_최신순1페이지_탐색캐시() {
		assertThat(ExhibitionListCacheResolver.resolve(search(null, "latest", null, null)))
				.contains(ExhibitionCache.ExploreLatestP1.INSTANCE);
	}

	@Test
	@DisplayName("정렬을 지정하지 않으면 최신순으로 수렴해 같은 캐시를 탄다")
	void resolve_정렬미지정_최신순캐시() {
		assertThat(ExhibitionListCacheResolver.resolve(search(null, null, null, null)))
				.contains(ExhibitionCache.ExploreLatestP1.INSTANCE);
	}

	@Test
	@DisplayName("탐색 정렬 축은 각자의 캐시를 탄다")
	void resolve_탐색정렬_각자캐시() {
		assertThat(ExhibitionListCacheResolver.resolve(search(null, "ending", null, null)))
				.contains(ExhibitionCache.ExploreEndingP1.INSTANCE);
		assertThat(ExhibitionListCacheResolver.resolve(search(null, "popular", null, null)))
				.contains(ExhibitionCache.ExplorePopularP1.INSTANCE);
	}

	@Test
	@DisplayName("커서가 있으면 2페이지 이후라 캐시하지 않는다")
	void resolve_커서있음_캐시안함() {
		assertThat(ExhibitionListCacheResolver.resolve(search(null, "latest", "eyJ4IjoxfQ==", null))).isEmpty();
	}

	@Test
	@DisplayName("지역 필터가 붙으면 캐시하지 않는다")
	void resolve_필터있음_캐시안함() {
		assertThat(ExhibitionListCacheResolver.resolve(search(null, "latest", null, "SEOUL"))).isEmpty();
	}

	@Test
	@DisplayName("홈 섹션은 각자의 캐시를 탄다")
	void resolve_홈섹션_각자캐시() {
		assertThat(ExhibitionListCacheResolver.resolve(search("ending-soon", "latest", null, null)))
				.contains(ExhibitionCache.HomeEndingSoon.INSTANCE);
		assertThat(ExhibitionListCacheResolver.resolve(search("opening-this-month", "latest", null, null)))
				.contains(ExhibitionCache.HomeNewThisMonth.INSTANCE);
		assertThat(ExhibitionListCacheResolver.resolve(search("free", "latest", null, null)))
				.contains(ExhibitionCache.HomeFree.INSTANCE);
	}

	@Test
	@DisplayName("선언한 목록 캐시는 모두 어떤 요청엔가 걸린다 — 워밍만 되고 조회에서 못 찾는 유령 캐시를 막는다")
	void resolve_선언한목록캐시_전부도달가능() {
		List<ExhibitionCriteria.Search> 첫화면요청 = List.of(search(null, "latest", null, null),
				search(null, "ending", null, null), search(null, "popular", null, null),
				search("ending-soon", "latest", null, null), search("opening-this-month", "latest", null, null),
				search("free", "latest", null, null));

		assertThat(첫화면요청.stream().map(ExhibitionListCacheResolver::resolve).flatMap(Optional::stream).toList())
				.containsExactlyInAnyOrder(ExhibitionCache.ExploreLatestP1.INSTANCE,
						ExhibitionCache.ExploreEndingP1.INSTANCE, ExhibitionCache.ExplorePopularP1.INSTANCE,
						ExhibitionCache.HomeEndingSoon.INSTANCE, ExhibitionCache.HomeNewThisMonth.INSTANCE,
						ExhibitionCache.HomeFree.INSTANCE);
	}

	@Test
	@DisplayName("무료 섹션이라도 최신순이 아니면 캐시하지 않는다")
	void resolve_무료섹션_다른정렬_캐시안함() {
		assertThat(ExhibitionListCacheResolver.resolve(search("free", "popular", null, null))).isEmpty();
	}

	@Test
	@DisplayName("거리순은 요청자 좌표에 따라 결과가 달라 캐시하지 않는다")
	void resolve_거리순_캐시안함() {
		assertThat(ExhibitionListCacheResolver.resolve(
				new ExhibitionCriteria.Search(null, null, null, null, null, LocalDate.now(), "distance",
						37.5, 127.0, null, null, null))).isEmpty();
	}

	@Test
	@DisplayName("기본 크기로 접히는 요청만 캐시한다 — 0은 어차피 20건이라 대상이고, 30은 아니다")
	void resolve_페이지크기_접힌값기준() {
		assertThat(ExhibitionListCacheResolver.resolve(withSize(20)))
				.contains(ExhibitionCache.ExploreLatestP1.INSTANCE);
		assertThat(ExhibitionListCacheResolver.resolve(withSize(0)))
				.contains(ExhibitionCache.ExploreLatestP1.INSTANCE);
		assertThat(ExhibitionListCacheResolver.resolve(withSize(30))).isEmpty();
	}

	@Test
	@DisplayName("로그인 여부는 캐시 대상 판정을 바꾸지 않는다 — 개인화는 캐시 밖에서 덮어쓴다")
	void resolve_요청자있음_캐시대상() {
		assertThat(ExhibitionListCacheResolver.resolve(
				new ExhibitionCriteria.Search(null, null, null, null, null, null, "latest", null, null, null,
						null, 42L))).contains(ExhibitionCache.ExploreLatestP1.INSTANCE);
	}

	private static ExhibitionCriteria.Search withSize(Integer size) {
		return new ExhibitionCriteria.Search(null, null, null, null, null, null, "latest", null, null, null, size,
				null);
	}
}
