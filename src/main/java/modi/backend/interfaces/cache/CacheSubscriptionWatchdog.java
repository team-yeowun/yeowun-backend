package modi.backend.interfaces.cache;

import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * - 구독이 붙어 있지 않으면 다시 붙인다
 *   - 컨테이너는 <b>한 번 붙은 뒤</b> 끊기는 경우만 스스로 재구독함
 *   - 기동 시점에 Redis가 죽어 있어 아예 못 붙은 경우는 스스로 회복하지 못함
 *   - 그 구멍을 메우는 것이 이 워치독이다
 *
 * - 구독을 못 한 서버는 조용히 손해를 본다
 *   - 무효화 방송을 못 받아 그 서버의 L1이 TTL까지 옛 값을 서빙함
 *   - 에러도 안 나고 요청도 200이라 밖에서는 정상으로 보임
 *   - 그래서 사람이 눈치채기 전에 코드가 되붙여야 함
 *
 * - 붙어 있으면 아무 일도 하지 않으므로 평시 비용은 사실상 없다
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheSubscriptionWatchdog {

	private final RedisMessageListenerContainer cacheInvalidationListenerContainer;

	@Scheduled(fixedDelayString = "${app.cache.invalidation.subscribe-retry-delay-ms:30000}")
	public void ensureSubscribed() {
		if (cacheInvalidationListenerContainer.isListening()) {
			return;
		}
		try {
			// start()는 멱등하지 않으므로 isListening()으로 걸러 부른다.
			cacheInvalidationListenerContainer.start();
			log.info("무효화 채널 재구독 성공 — 이 서버가 다시 방송을 받는다");
		} catch (RuntimeException e) {
			log.warn("무효화 채널 재구독 실패 — 다음 주기에 다시 시도한다: {}", e.getMessage());
		}
	}
}
