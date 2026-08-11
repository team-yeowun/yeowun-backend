package modi.backend.application.exhibition.view;

import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import modi.backend.application.exhibition.ExhibitionResult;
import modi.backend.application.exhibition.cache.ExhibitionCache;
import modi.backend.domain.exhibition.catalog.ExhibitionViewCounter;
import modi.backend.support.cache.CacheManager;

/**
 * 누산된 조회수를 정본({@code exhibitions.our_view_count})으로 옮기는 유스케이스.
 *
 * <p>수거 → 반영 → 확정 세 박자로 돈다. 반영이 실패하면 되돌려서 그 회차의 조회수가 통째로 사라지지 않게 하고,
 * 확정은 <b>반드시 반영이 끝난 뒤</b>에만 한다 — 순서가 뒤집히면 반영 실패분이 조용히 사라진다.
 *
 * <p>수거가 원자적이라 인스턴스가 둘이어도 한쪽만 가져간다. 워밍과 달리 이 작업은 <b>누적 연산</b>이라
 * 중복 실행이 곧 조회수 뻥튀기이므로, 그 성질이 여기서 가장 중요하다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExhibitionViewCountService {

	private final ExhibitionViewCounter viewCounter;
	private final ExhibitionViewCountApplier viewCountApplier;
	private final CacheManager cacheManager;

	/** 누산분을 정본에 반영한다. 반영할 것이 없으면 아무 일도 하지 않는다. */
	public ExhibitionResult.ViewCountFlush flush() {
		Map<Long, Long> deltas = viewCounter.drain();
		if (deltas.isEmpty()) {
			return new ExhibitionResult.ViewCountFlush(0, 0);
		}
		try {
			int updated = viewCountApplier.apply(deltas);
			viewCounter.discardDrained();
			evictDetailCaches(deltas.keySet());
			long views = deltas.values().stream().mapToLong(Long::longValue).sum();
			log.info("조회수 반영: 전시 {}건 / 조회 {}회", updated, views);
			return new ExhibitionResult.ViewCountFlush(updated, views);
		} catch (RuntimeException e) {
			// 반영에 실패했으니 수거분을 누산기로 돌려놓는다 — 다음 회차가 다시 가져간다.
			viewCounter.restoreDrained();
			throw e;
		}
	}

	/**
	 * - 반영한 전시의 상세 캐시를 지움
	 *   - 캐시된 상세는 담길 때의 정본 값을 들고 있음
	 *   - 정본이 올라가고 누산분이 0으로 돌아가면 정본(옛값) + 0이 되어 표시가 뒤로 감
	 *   - 지우지 않으면 사용자가 보던 조회수가 줄어드는 것으로 보임
	 *
	 * - 지울 대상은 이번 창에 실제로 조회된 전시뿐
	 *   - 수거 결과의 키가 그대로 목록이 됨
	 *
	 * - 이 경로는 방송량 관찰 대상
	 *   - 6시간에 한 번, 그 사이 조회된 전시 수만큼 evict와 방송이 나감
	 *   - 수가 커지면 키별 evict 대신 "상세 캐시 전체 비우기" 하나로 바꾸는 편이 쌈
	 */
	private void evictDetailCaches(Set<Long> exhibitionIds) {
		exhibitionIds.forEach(exhibitionId ->
				cacheManager.evict(ExhibitionCache.ExhibitionDetail.INSTANCE, String.valueOf(exhibitionId)));
	}
}
