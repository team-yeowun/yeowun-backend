package modi.backend.infra.exhibition.redis;

import java.util.HashMap;
import java.util.Map;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import modi.backend.domain.exhibition.catalog.ExhibitionViewCounter;

/**
 * 조회수 누산기의 Redis 어댑터. 해시 하나({@value #DELTA_KEY})에 전시 id → 증가량을 모으고,
 * 배치가 통째로 가져가 MySQL에 반영한다.
 *
 * <p><b>수거가 원자적인 이유</b>: {@code RENAME}은 Redis 단일 명령이라 인스턴스가 둘이어도 누산 키를
 * 가져가는 쪽은 하나뿐이다. 진 쪽은 "키 없음"으로 떨어져 빈 맵을 받는다 — 별도의 분산 락 없이
 * <b>같은 델타가 두 번 반영되는 일</b>이 막힌다. 워밍과 달리 flush는 누적 연산이라 중복 실행이 곧 조회수 뻥튀기다.
 *
 * <p><b>되돌리기가 안전한 이유</b>: 누산이 덧셈이라 그 사이에 들어온 조회와 교환법칙이 성립한다.
 * 그래서 DB 반영에 실패하면 수거분을 그대로 되더하면 되고, 그 창의 조회가 유실되지 않는다.
 *
 * <p>운영 Redis는 비영속이라 재시작하면 누산분이 사라진다. 그래도 되는 값이다 — 진실의 원천은
 * {@code exhibitions.our_view_count}이고, 잃는 것은 마지막 반영 이후의 증가분뿐이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisExhibitionViewCounter implements ExhibitionViewCounter {

	/** 누산 중인 창. 조회가 들어올 때마다 여기에 쌓인다. */
	public static final String DELTA_KEY = "exhibition:view:delta";

	/** 수거해 반영을 기다리는 창. 반영이 확정되면 지우고, 실패하면 누산 창으로 되돌린다. */
	public static final String DRAINING_KEY = "exhibition:view:delta:draining";

	private final StringRedisTemplate redisTemplate;

	@Override
	public long increase(Long exhibitionId) {
		if (exhibitionId == null) {
			return 0;
		}
		try {
			Long accumulated = redisTemplate.opsForHash().increment(DELTA_KEY, String.valueOf(exhibitionId), 1L);
			return accumulated == null ? 0 : accumulated;
		} catch (Exception e) {
			// 조회수는 부가 값이다 — 누산기가 죽었다고 상세 응답까지 죽일 이유가 없다.
			log.warn("조회수 누산 실패, 이번 조회는 세지 않는다: exhibitionId={}", exhibitionId, e);
			return 0;
		}
	}

	@Override
	public Map<Long, Long> drain() {
		try {
			redisTemplate.rename(DELTA_KEY, DRAINING_KEY);
		} catch (Exception e) {
			// 누산분이 없거나, 다른 인스턴스가 이미 가져갔다. 둘 다 "이번엔 반영할 것이 없다"로 같다.
			log.debug("수거할 누산분 없음: {}", e.getMessage());
			return Map.of();
		}
		try {
			return read(DRAINING_KEY);
		} catch (Exception e) {
			log.warn("수거분 읽기 실패, 다음 회차가 다시 가져간다", e);
			return Map.of();
		}
	}

	@Override
	public void discardDrained() {
		try {
			redisTemplate.delete(DRAINING_KEY);
		} catch (Exception e) {
			log.warn("수거분 폐기 실패 — 다음 수거가 덮어쓴다(이미 반영됐으므로 중복 반영은 아니다)", e);
		}
	}

	@Override
	public void restoreDrained() {
		try {
			Map<Long, Long> drained = read(DRAINING_KEY);
			drained.forEach((exhibitionId, delta) ->
					redisTemplate.opsForHash().increment(DELTA_KEY, String.valueOf(exhibitionId), delta));
			redisTemplate.delete(DRAINING_KEY);
		} catch (Exception e) {
			log.warn("수거분 되돌리기 실패 — 그 창의 조회수는 유실된다", e);
		}
	}

	private Map<Long, Long> read(String key) {
		Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);
		Map<Long, Long> deltas = new HashMap<>(entries.size());
		entries.forEach((id, delta) -> deltas.put(Long.valueOf(id.toString()), Long.valueOf(delta.toString())));
		return deltas;
	}
}
