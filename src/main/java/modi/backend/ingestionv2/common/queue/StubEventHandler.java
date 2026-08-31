package modi.backend.ingestionv2.common.queue;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import modi.backend.ingestionv2.common.IngestionProperties;
import modi.backend.ingestionv2.common.event.IngestionEventType;

/**
 * 부하 실험 전용 소비 핸들러 - {@code consume-handler=STUB} 일 때만 등록된다.
 *
 * <ul>
 *   <li>모든 이벤트를 맡는다 - 도메인 핸들러가 한 개도 등록되지 않은 무대라 배정표가 필요 없다</li>
 *   <li>하는 일은 정해진 시간만큼 머무는 것뿐 - 벤더 호출·DB 쓰기가 없어 배달 계층의 처리량과 배분만 남는다</li>
 *   <li>만지는 것은 {@code lab:} 접두 키 셋뿐 - 행 마커 {@code outbox:}·잡 락 {@code lock:} 과 갈라져 있어
 *       관측 스크립트의 키 집계를 오염시키지 않는다</li>
 *   <li>기록은 처리 끝에서 한 번 - 처리 도중 죽은 항목이 회수되어 다시 오는 것을 중복으로 세지 않기 위함</li>
 *   <li>집합에 처음 들어간 것이 아니면 중복 소비 - 같은 사실이 두 번 처리됐다는 뜻이라 따로 센다</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.ingestion.v2", name = "consume-handler", havingValue = "STUB")
class StubEventHandler implements IngestionEventHandler {

	/** 처리한 원천 키의 집합. 크기가 곧 고유 처리 건수다. */
	static final String CONSUMED_IDS_KEY = "lab:consumed:ids";

	/** 이미 집합에 있던 키가 다시 온 횟수. */
	static final String CONSUMED_DUP_KEY = "lab:consumed:dup";

	/** 처리 호출 횟수. 중복을 포함한 총량이다. */
	static final String CONSUMED_COUNT_KEY = "lab:consumed:count";

	private final StringRedisTemplate redisTemplate;
	private final IngestionProperties properties;

	@Override
	public boolean supports(IngestionEventType type) {
		return true;
	}

	@Override
	public void handle(String vendorKey) {
		sleepForLatency();
		Long added = redisTemplate.opsForSet().add(CONSUMED_IDS_KEY, vendorKey);
		if (added != null && added == 0L) {
			redisTemplate.opsForValue().increment(CONSUMED_DUP_KEY);
		}
		redisTemplate.opsForValue().increment(CONSUMED_COUNT_KEY);
	}

	private void sleepForLatency() {
		long latencyMs = properties.stubLatencyMs();
		if (latencyMs <= 0) {
			return;
		}
		try {
			Thread.sleep(latencyMs);
		} catch (InterruptedException interrupted) {
			// 구독 스레드가 종료 중이다. 표시만 되살리고 처리는 계속한다 - 여기서 예외를 던지면 미처리로 남는다.
			Thread.currentThread().interrupt();
		}
	}
}
