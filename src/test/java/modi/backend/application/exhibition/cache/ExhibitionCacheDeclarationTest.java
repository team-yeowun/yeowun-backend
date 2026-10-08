package modi.backend.application.exhibition.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import modi.backend.support.cache.CacheType;
import modi.backend.support.cache.MyCache;

/**
 * - 캐시 선언이 설계(목록 2단 · 상세 Redis 단독 · TTL 10분/30분)와 같은지 고정
 *   - TTL·타입은 숫자 하나로 바뀌는 성질이라 런타임 동작만으로는 회귀를 못 잡음
 */
class ExhibitionCacheDeclarationTest {

	@Test
	@DisplayName("목록 7종은 L1 10분 + Redis 30분 2단이다")
	void 목록_2단_TTL() {
		assertThat(ExhibitionCache.LISTS).hasSize(7);
		for (MyCache list : ExhibitionCache.LISTS) {
			assertThat(list.getType()).as(list.getName()).isEqualTo(CacheType.TWO_TIER);
			assertThat(list.getTtl()).as(list.getName()).isEqualTo(Duration.ofMinutes(10));
			assertThat(((MyCache.TwoTierCache) list).getRedisTtl()).as(list.getName())
					.isEqualTo(Duration.ofMinutes(30));
		}
	}

	@Test
	@DisplayName("상세는 로컬 복사본 없이 Redis만 쓰고 TTL은 30분이다")
	void 상세_Redis단독() {
		MyCache detail = ExhibitionCache.ExhibitionDetail.INSTANCE;

		assertThat(detail.getType()).isEqualTo(CacheType.REDIS);
		assertThat(detail.getTtl()).isEqualTo(Duration.ofMinutes(30));
		assertThat(ExhibitionCache.LISTS).doesNotContain(detail);
	}

	@Test
	@DisplayName("조립 대상(ALL)은 목록 7종 + 상세 1종이다")
	void 전체목록() {
		assertThat(ExhibitionCache.ALL).hasSize(8)
				.containsAll(ExhibitionCache.LISTS)
				.contains(ExhibitionCache.ExhibitionDetail.INSTANCE);
	}
}
