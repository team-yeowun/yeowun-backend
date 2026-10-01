package modi.backend.application.exhibition.ranking;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import modi.backend.application.exhibition.ExhibitionResult;
import modi.backend.application.exhibition.list.ExhibitionListAssembler;
import modi.backend.domain.exhibition.catalog.Exhibition;
import modi.backend.domain.exhibition.catalog.ExhibitionPlace;
import modi.backend.domain.exhibition.catalog.ExhibitionQueryRepository;
import modi.backend.domain.exhibition.catalog.ExhibitionRepository;
import modi.backend.domain.exhibition.catalog.ExhibitionType;
import modi.backend.domain.exhibition.ranking.ExhibitionRankingRepository;
import modi.backend.domain.exhibition.ranking.RankingBoards;
import modi.backend.domain.exhibition.ranking.RankingCandidate;
import modi.backend.domain.exhibition.ranking.RankingEntry;
import modi.backend.domain.exhibition.ranking.RankingPlan;
import modi.backend.domain.exhibition.ranking.RankingTieBreak;
import modi.backend.domain.exhibition.ranking.RankingType;
import modi.backend.support.time.AppTime;

/**
 * - 전시 랭킹 유스케이스: 기록 · 재계산 · TOP-N · 개별 순위 · 홈 배너 TOP3
 *   - 랭킹의 원본은 Redis 날짜 버킷, MySQL에서는 카탈로그 정보(진행 중 후보·개막일·배너 카드)만 읽음
 *   - 어떤 날짜를 어떤 가중치로 합칠지는 {@link RankingBoards}의 전략이 정하고, 여기는 순서만 조율
 *
 * - 재계산을 두 앱이 각자 실행해도 락을 두지 않음
 *   - 같은 원본·같은 후보에서 다시 만든 결과로 덮어써 몇 번 돌아도 같음
 *
 * - TOP-N({@link #top})과 개별 순위({@link #rankOf})는 API로 열지 않은 내부 기능
 *   - 지금 쓰는 곳은 홈 배너({@link #banners}) 하나, 외부에 열 때 컨트롤러만 붙이면 됨
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExhibitionRankingService {

	/** 홈 배너 노출 수. */
	private static final int BANNER_SIZE = 3;

	/** 재계산 사이에 지워진 전시가 있어도 3개를 채우도록 여유 있게 읽는 수. */
	private static final int BANNER_LOOKAHEAD = BANNER_SIZE * 2;

	private final RankingBoards rankingBoards;
	private final ExhibitionRankingRepository rankingRepository;
	private final ExhibitionQueryRepository exhibitionQueryRepository;
	private final ExhibitionRepository exhibitionRepository;
	private final ExhibitionListAssembler listAssembler;

	/**
	 * - 상세 조회 1번을 랭킹에 기록
	 *   - CATALOG만 셈 (개인 전시는 본인만 보므로 순위의 의미가 없음)
	 *   - 저장소 장애는 저장소가 삼켜 상세 응답에 영향 없음
	 */
	public void recordView(ExhibitionResult.Detail detail) {
		if (!ExhibitionType.CATALOG.name().equals(detail.type())) {
			return;
		}
		LocalDate today = today();
		rankingRepository.recordView(detail.exhibitionId(), today, rankingBoards.increments(today));
	}

	/**
	 * - 활성 순위판을 원본에서 다시 만들고, 보존 기간 밖 버킷을 지움
	 *   - 후보는 순위판마다 다시 읽지 않고 한 번만 읽음 (같은 시점의 후보로 모든 순위판을 만듦)
	 *   - 동점 점수 상한은 순위판마다 다름 (계획의 가장 작은 가중치에서 나옴)
	 */
	public ExhibitionResult.RankingRebuild rebuildAll() {
		LocalDate today = today();
		List<RankingCandidate> candidates = exhibitionQueryRepository.findOngoingCatalogCandidates(today);
		Map<RankingType, Long> boards = new EnumMap<>(RankingType.class);
		for (RankingPlan plan : rankingBoards.plans(today)) {
			RankingTieBreak tieBreak = RankingTieBreak.byNewestOpening(candidates, plan.tieBreakCap());
			boards.put(plan.type(), rankingRepository.rebuild(plan, tieBreak));
		}
		rankingRepository.purgeBefore(rankingBoards.keepFrom(today));
		log.debug("랭킹 재계산: 후보 {}건, 순위판 {}", candidates.size(), boards);
		return new ExhibitionResult.RankingRebuild(candidates.size(), boards);
	}

	/** 순위판 상위 {@code size}개(내부 기능). */
	public List<ExhibitionResult.Ranked> top(RankingType type, int size) {
		requireActive(type);
		if (size < 1) {
			throw new IllegalArgumentException("상위 몇 개를 볼지는 1 이상이어야 함: " + size);
		}
		return rankingRepository.top(type, size).stream().map(ExhibitionResult.Ranked::from).toList();
	}

	/** 전시 하나의 순위(내부 기능). 순위판에 없으면 순위·점수가 null. */
	public ExhibitionResult.RankPosition rankOf(RankingType type, long exhibitionId) {
		requireActive(type);
		long total = rankingRepository.count(type);
		return rankingRepository.find(type, exhibitionId)
				.map(entry -> new ExhibitionResult.RankPosition(type, exhibitionId, entry.rank(), entry.score(), total))
				.orElseGet(() -> new ExhibitionResult.RankPosition(type, exhibitionId, null, null, total));
	}

	/**
	 * - 홈 배너: 배너 순위판 상위 3개를 기존 배너 응답 형태로 조립
	 *   - 순위 순서를 그대로 지킴 (DB 조회 결과 순서를 쓰지 않음)
	 *   - 재계산 사이에 지워진 전시는 건너뛰고 다음 순위로 채움
	 */
	@Transactional(readOnly = true)
	public List<ExhibitionResult.Banner> banners() {
		List<Long> rankedIds = rankingRepository.top(rankingBoards.bannerType(), BANNER_LOOKAHEAD).stream()
				.map(RankingEntry::exhibitionId)
				.toList();
		if (rankedIds.isEmpty()) {
			return List.of();
		}
		Map<Long, Exhibition> exhibitionsById = exhibitionRepository.findAllActiveByIds(rankedIds).stream()
				.collect(Collectors.toMap(Exhibition::getId, Function.identity()));
		List<Exhibition> ranked = rankedIds.stream()
				.map(exhibitionsById::get)
				.filter(Objects::nonNull)
				.limit(BANNER_SIZE)
				.toList();
		Map<Long, ExhibitionPlace> placesById = listAssembler.placesById(ranked);
		return ranked.stream()
				.map(e -> ExhibitionResult.Banner.from(e,
						placesById.getOrDefault(e.getExhibitionPlaceId(), ExhibitionPlace.unknown())))
				.toList();
	}

	private void requireActive(RankingType type) {
		if (!rankingBoards.isActive(type)) {
			throw new IllegalArgumentException("활성화되지 않은 순위판: " + type);
		}
	}

	private static LocalDate today() {
		return LocalDate.now(AppTime.KST);
	}
}
