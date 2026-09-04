package modi.backend.ingestionv2.common.lock;

import java.time.Duration;
import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 잡 단위 리더 락과 비교 실험용 행 마커의 원시 연산 - {@code SET key owner NX PX ttl}.
 *
 * <ul>
 *   <li>먼저 쓴 쪽만 참을 받는다 - 인스턴스가 몇 대든 같은 키에 대해 한 번만 참이 된다</li>
 *   <li>운영 Outbox 행 소유권은 MySQL SKIP LOCKED가 맡고, Redis 행 마커는 이전 전략 비교 실험에만 사용</li>
 *   <li>소유자 값을 담는 이유 - 만료 뒤 다른 인스턴스가 같은 키를 잡았을 때 이전 소유자의 해제가 남의 락을 지우지 않게</li>
 *   <li>해제는 Lua 한 덩어리 - 값 비교와 삭제 사이에 만료가 끼면 비원자 해제가 남의 락을 지운다</li>
 *   <li>대기하지 않는다 - 획득 실패는 "다른 인스턴스가 맡았다"는 사실이고 이 슬라이스에서 대기는 곧 락 병목</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisMarkerLock {

	/** 소유자가 같을 때만 지운다. 값 비교와 삭제가 한 덩어리라야 만료 직후의 남의 락을 지우지 않는다. */
	private static final String COMPARE_AND_DELETE = """
			if redis.call('get', KEYS[1]) == ARGV[1] then
				return redis.call('del', KEYS[1])
			else
				return 0
			end
			""";

	private final StringRedisTemplate redisTemplate;

	/** 키를 선점한다. 이미 누가 잡고 있으면 거짓 - 호출자는 기다리지 않고 그 대상을 건너뛴다. */
	public boolean tryAcquire(String key, String owner, Duration ttl) {
		Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, owner, ttl);
		return Boolean.TRUE.equals(acquired);
	}

	/** 소유자가 같을 때만 해제한다. 만료돼 사라졌거나 다른 소유자면 아무것도 하지 않는다. */
	public boolean release(String key, String owner) {
		Long deleted = redisTemplate.execute(RedisScript.of(COMPARE_AND_DELETE, Long.class), List.of(key), owner);
		return deleted != null && deleted > 0;
	}
}
