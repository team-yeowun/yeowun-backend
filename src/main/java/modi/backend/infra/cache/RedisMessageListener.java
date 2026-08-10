package modi.backend.infra.cache;

import java.nio.charset.StandardCharsets;

import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.SubscriptionListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import modi.backend.support.cache.CacheInvalidationMetrics;
import modi.backend.support.cache.CacheManager;

/**
 * - 무효화 메시지 수신부
 *   - {@code "캐시이름:엔트리키"} 문자열을 갈라 자기 L1에서만 지움
 *   - 값은 오지 않음 — 새 값은 다음 조회가 L2에서 되채움
 *
 * - 예외를 밖으로 던지지 않음(틈 ③이 틈 ②로 번지는 것을 차단)
 *   - 한 건의 처리 실패가 구독 자체를 죽이면 그 서버는 이후 모든 메시지를 놓침
 *   - 피해가 가장 작은 지점의 사고가 가장 큰 지점의 사고로 바뀜
 *
 * - 실패하면 로그만 남기지 않고 L1을 통째로 비움
 *   - 캐시에서 안전한 실패는 옛 값을 지키는 것이 아니라 비우는 것
 *   - 파싱조차 안 되는 새 포맷이 와도 이 폴백이 받음
 *   - 비용은 고정 키의 L2 재조회뿐
 *
 * - {@link SubscriptionListener}를 직접 구현함
 *   - 구독이 확인되는 시점(최초·재구독)마다 공백 구간을 모르고도 안전해지려면 L1 전체 삭제가 필요
 *   - 이 콜백은 컨테이너에 <b>직접</b> 등록된 리스너에게만 감 — 어댑터로 감싸면 영영 호출되지 않음
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisMessageListener implements MessageListener, SubscriptionListener {

	private final CacheManager cacheManager;
	private final CacheInvalidationMetrics invalidationMetrics;

	@Override
	public void onMessage(Message message, byte[] pattern) {
		try {
			String body = new String(message.getBody(), StandardCharsets.UTF_8);
			String[] parts = body.split(":", 2);
			if (parts.length != 2) {
				throw new IllegalArgumentException("무효화 메시지 형식이 아니다: " + body);
			}
			cacheManager.evictLocal(parts[0], parts[1]);    // 없어도, 두 번이어도 무해(멱등)
			invalidationMetrics.received();
		} catch (Exception e) {
			log.warn("무효화 처리 실패 → L1 전체 삭제로 안전하게 폴백한다", e);
			invalidationMetrics.receiveFailed();
			cacheManager.clearLocal();
		}
	}

	/**
	 * - 구독이 확인된 시점(최초 구독 포함)에 L1을 전부 비움
	 *   - 끊겨 있던 동안 무엇을 놓쳤는지 몰라도 안전해짐
	 *   - 메시지가 삭제 키만 담고 데이터 원본이 L2 하나라, 재생(replay) 대신 재조회(re-pull)로 충분
	 *   - 비용은 고정 키 7종의 L2 재조회뿐
	 */
	@Override
	public void onChannelSubscribed(byte[] channel, long count) {
		log.info("무효화 채널 구독 확인 — L1을 비운다: channel={}, 구독자수={}",
				new String(channel, StandardCharsets.UTF_8), count);
		cacheManager.clearLocal();
		invalidationMetrics.resubscribed();
	}

	@Override
	public void onChannelUnsubscribed(byte[] channel, long count) {
		log.warn("무효화 채널 구독 해제 — 재구독 전까지 이 서버의 L1은 TTL에만 의존한다: channel={}",
				new String(channel, StandardCharsets.UTF_8));
	}
}
