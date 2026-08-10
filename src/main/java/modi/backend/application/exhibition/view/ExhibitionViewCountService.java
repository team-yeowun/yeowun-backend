package modi.backend.application.exhibition.view;

import java.util.Map;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import modi.backend.application.exhibition.ExhibitionResult;
import modi.backend.domain.exhibition.catalog.ExhibitionViewCounter;

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

	/** 누산분을 정본에 반영한다. 반영할 것이 없으면 아무 일도 하지 않는다. */
	public ExhibitionResult.ViewCountFlush flush() {
		Map<Long, Long> deltas = viewCounter.drain();
		if (deltas.isEmpty()) {
			return new ExhibitionResult.ViewCountFlush(0, 0);
		}
		try {
			int updated = viewCountApplier.apply(deltas);
			viewCounter.discardDrained();
			long views = deltas.values().stream().mapToLong(Long::longValue).sum();
			log.info("조회수 반영: 전시 {}건 / 조회 {}회", updated, views);
			return new ExhibitionResult.ViewCountFlush(updated, views);
		} catch (RuntimeException e) {
			// 반영에 실패했으니 수거분을 누산기로 돌려놓는다 — 다음 회차가 다시 가져간다.
			viewCounter.restoreDrained();
			throw e;
		}
	}
}
