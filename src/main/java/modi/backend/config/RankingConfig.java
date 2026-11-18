package modi.backend.config;

import java.util.List;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import modi.backend.domain.exhibition.ranking.ExponentialDecayRankingStrategy;
import modi.backend.domain.exhibition.ranking.RankingBoards;
import modi.backend.domain.exhibition.ranking.RankingStrategy;
import modi.backend.domain.exhibition.ranking.SlidingWindowRankingStrategy;

/**
 * - 랭킹 전략 등록과 순위판 조립
 *   - 전략은 Spring을 모르는 도메인 객체라 여기서 빈으로 등록
 *   - 등록된 전략 전부가 {@link RankingBoards}로 모이고, 그중 설정의 activeTypes만 기록·재계산됨
 *
 * - 새 알고리즘 추가 = 여기 빈 한 줄 + 전략 클래스 + {@code RankingType} 상수
 *   - 등록만 해 두면 날짜 버킷 보존 기간이 그 알고리즘의 창까지 자동으로 늘어남
 */
@Configuration
@EnableConfigurationProperties(RankingProperties.class)
public class RankingConfig {

	@Bean
	public RankingStrategy recentThreeDaysRankingStrategy() {
		return SlidingWindowRankingStrategy.recent3d();
	}

	@Bean
	public RankingStrategy dailyRankingStrategy() {
		return SlidingWindowRankingStrategy.daily();
	}

	@Bean
	public RankingStrategy weeklyRankingStrategy() {
		return SlidingWindowRankingStrategy.weekly();
	}

	@Bean
	public RankingStrategy trendingRankingStrategy() {
		return ExponentialDecayRankingStrategy.trending();
	}

	@Bean
	public RankingBoards rankingBoards(List<RankingStrategy> strategies, RankingProperties properties) {
		return new RankingBoards(strategies, properties.activeTypes(), properties.bannerType());
	}
}
