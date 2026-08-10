package modi.backend.infra.cache;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * - 캐시 무효화 이벤트 발행자
 *   - 단일 채널에 {@code "캐시이름:엔트리키"} 형태의 문자열 하나를 발행
 *   - 예: {@code "HomeBanners:ALL"}
 *
 * - 채널을 키마다 분리하지 않음
 *   - 구독 설정이 개별 캐시 목록과 결합되는 것을 방지
 *   - 캐시가 추가되어도 구독 설정을 변경할 필요가 없음
 *
 * - 이벤트 발행 실패가 본 요청까지 실패시키면 안 됨
 *   - 캐시 무효화는 부가적인 작업이므로
 *   - 발행 실패가 본 요청의 성공/실패에 영향을 주지 않도록 처리
 */
@Component
@RequiredArgsConstructor
public class RedisPublisher {

	public static final String TOPIC = "cache-invalidation";

	private final StringRedisTemplate redisTemplate;

	public void publish(String message) {
		redisTemplate.convertAndSend(TOPIC, message);
	}
}
