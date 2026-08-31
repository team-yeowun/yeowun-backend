package modi.backend.ingestionv2.common.lock;

import java.time.Duration;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import modi.backend.ingestionv2.common.IngestionProperties;

/**
 * 잡 단위 단일 실행 - 같은 주기에 깨어난 인스턴스 중 한 대만 돈다.
 *
 * <ul>
 *   <li>대상은 회수·정리·트리밍 셋 - 셋 다 전체를 훑는 잡이라 두 대가 같은 대상을 동시에 훑을 이유가 없다</li>
 *   <li>아웃박스 발송에는 쓰지 않는다 - 발송은 행 단위 마커가 맡고, 잡 락을 걸면 한 대만 일하게 되어 병렬성이 사라진다</li>
 *   <li>수집 회차에도 쓰지 않는다 - 회차 키 유일 제약이 이미 단일 실행을 만든다</li>
 *   <li>획득 실패는 조용한 종료 - 다른 인스턴스가 그 주기를 맡았다는 사실일 뿐 오류가 아니다</li>
 *   <li>끝나면 해제 - TTL 만 믿고 두면 잡 주기가 TTL 보다 짧을 때 다음 주기가 통째로 건너뛰어진다</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IngestionJobLock {

	private static final String KEY_PREFIX = "lock:";

	private final RedisMarkerLock markerLock;
	private final IngestionProperties properties;

	/** 락을 잡은 인스턴스에서만 잡을 돌린다. 못 잡으면 아무것도 하지 않고 돌아온다. */
	public void runIfAcquired(String jobName, Runnable job) {
		String key = KEY_PREFIX + jobName;
		String owner = properties.consumerName();
		Duration ttl = Duration.ofMillis(properties.jobLockTtlMs());
		if (!markerLock.tryAcquire(key, owner, ttl)) {
			log.debug("다른 인스턴스가 맡은 주기라 건너뜁니다. job={}", jobName);
			return;
		}
		try {
			job.run();
		} finally {
			markerLock.release(key, owner);
		}
	}
}
