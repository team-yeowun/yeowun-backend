package modi.backend.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import modi.backend.domain.exhibition.ranking.RankingType;

/**
 * - 전시 랭킹 설정. {@code app.exhibition.ranking.*} 바인딩
 *   - activeTypes: 기록·재계산할 순위판 (조회 1번에 순위판 수만큼 ZINCRBY가 나감)
 *   - bannerType: 홈 배너가 읽을 순위판, activeTypes 안에 있어야 함
 *   - 재계산 주기(rebuild-cron)는 스케줄러가 직접 읽음
 *
 * - 알고리즘 교체 = bannerType 변경, 코드 수정 없음
 */
@ConfigurationProperties(prefix = "app.exhibition.ranking")
public record RankingProperties(List<RankingType> activeTypes, RankingType bannerType) {

	public RankingProperties {
		if (activeTypes == null || activeTypes.isEmpty()) {
			activeTypes = List.of(RankingType.RECENT_3D);
		}
		if (bannerType == null) {
			bannerType = RankingType.RECENT_3D;
		}
	}
}
