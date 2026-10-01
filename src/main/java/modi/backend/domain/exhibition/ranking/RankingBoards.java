package modi.backend.domain.exhibition.ranking;

import java.time.LocalDate;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * - 등록된 랭킹 전략과 그중 유지할 순위판 (일급 컬렉션)
 *   - 등록 = 코드에 있는 알고리즘 전부, 활성 = 기록·재계산할 순위판, 배너 = 홈 배너가 읽을 순위판
 *   - 설정이 어긋나면 생성자에서 예외 → 앱이 뜨지 않음
 *
 * - 날짜 버킷 보존 기간은 활성이 아니라 등록된 전략 기준
 *   - 가장 긴 창(7일)만큼 원본을 남겨, 배너 알고리즘을 바꿔도 첫 재계산부터 데이터가 다 있음
 */
public final class RankingBoards {

	/** 보존 경계 앞에 하루를 더 둠. 자정 직후 두 앱의 날짜 계산이 엇갈려도 필요한 버킷이 지워지지 않게. */
	private static final int RETENTION_MARGIN_DAYS = 1;

	private final Map<RankingType, RankingStrategy> registered;
	private final List<RankingStrategy> active;
	private final RankingType bannerType;

	public RankingBoards(Collection<RankingStrategy> strategies, Collection<RankingType> activeTypes,
			RankingType bannerType) {
		this.registered = index(strategies);
		this.active = activate(registered, activeTypes);
		this.bannerType = Objects.requireNonNull(bannerType, "bannerType");
		if (!isActive(bannerType)) {
			throw new IllegalArgumentException("배너 순위판은 활성 목록에 있어야 함: " + bannerType);
		}
	}

	/** 활성 순위판마다 오늘 기준 순위 계획. */
	public List<RankingPlan> plans(LocalDate today) {
		return active.stream().map(strategy -> strategy.plan(today)).toList();
	}

	/** 오늘 조회 1번이 활성 순위판마다 더할 점수. 오늘을 창에 넣지 않는 순위판은 빠짐. */
	public List<BoardIncrement> increments(LocalDate today) {
		return plans(today).stream()
				.filter(plan -> plan.weightOf(today) > 0)
				.map(plan -> new BoardIncrement(plan.type(), plan.weightOf(today)))
				.toList();
	}

	/** 이 날짜부터의 버킷은 남기고 그 전은 지워도 되는 경계. */
	public LocalDate keepFrom(LocalDate today) {
		return registered.values().stream()
				.map(strategy -> strategy.plan(today).oldestDay())
				.min(LocalDate::compareTo)
				.orElseThrow()
				.minusDays(RETENTION_MARGIN_DAYS);
	}

	public RankingType bannerType() {
		return bannerType;
	}

	public boolean isActive(RankingType type) {
		return active.stream().anyMatch(strategy -> strategy.type() == type);
	}

	private static Map<RankingType, RankingStrategy> index(Collection<RankingStrategy> strategies) {
		if (strategies == null || strategies.isEmpty()) {
			throw new IllegalArgumentException("랭킹 전략이 하나도 등록되지 않음");
		}
		Map<RankingType, RankingStrategy> byType = new EnumMap<>(RankingType.class);
		for (RankingStrategy strategy : strategies) {
			if (byType.putIfAbsent(strategy.type(), strategy) != null) {
				throw new IllegalArgumentException("같은 순위판 종류의 전략이 둘 등록됨: " + strategy.type());
			}
		}
		return byType;
	}

	private static List<RankingStrategy> activate(Map<RankingType, RankingStrategy> registered,
			Collection<RankingType> activeTypes) {
		if (activeTypes == null || activeTypes.isEmpty()) {
			throw new IllegalArgumentException("활성 순위판이 하나 이상 있어야 함");
		}
		Set<RankingType> distinct = new LinkedHashSet<>(activeTypes);
		return distinct.stream().map(type -> {
			RankingStrategy strategy = registered.get(type);
			if (strategy == null) {
				throw new IllegalArgumentException("등록되지 않은 순위판을 활성화함: " + type);
			}
			return strategy;
		}).toList();
	}
}
