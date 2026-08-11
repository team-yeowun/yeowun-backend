package modi.backend.infra.cache;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.connection.Message;

import modi.backend.support.cache.CacheInvalidationMetrics;
import modi.backend.support.cache.CacheManager;

/**
 * - 수신부는 "실패했을 때 무엇을 하는가"가 전부다
 *   - 예외를 밖으로 내면 메시지 한 건의 실패가 구독 전체를 죽임(틈 ③ → 틈 ②로 확산)
 *   - 그래서 예외 비전파와 "안전한 방향(비우기)으로 실패"를 함께 고정한다
 *
 * - 구독 확인 시 L1 전체 삭제도 여기서 본다(틈 ②)
 *   - 끊긴 동안 무엇을 놓쳤는지 몰라도 안전해지는 유일한 수단
 */
@ExtendWith(MockitoExtension.class)
class RedisMessageListenerTest {

	@Mock
	private CacheManager cacheManager;
	@Mock
	private CacheInvalidationMetrics invalidationMetrics;

	@InjectMocks
	private RedisMessageListener listener;

	private static Message 메시지(String body) {
		return new DefaultMessage("cache-invalidation".getBytes(UTF_8), body.getBytes(UTF_8));
	}

	@Test
	@DisplayName("정상 메시지는 해당 키의 L1만 지운다")
	void onMessage_정상_키하나만_삭제() {
		listener.onMessage(메시지("HomeBanners:ALL"), null);

		verify(cacheManager).evictLocal("HomeBanners", "ALL");
		verify(cacheManager, never()).clearLocal();
		verify(invalidationMetrics).received();
	}

	@Test
	@DisplayName("상세처럼 키 자체에 콜론이 있어도 캐시 이름과 키로만 가른다")
	void onMessage_키에_콜론포함() {
		listener.onMessage(메시지("ExhibitionDetail:12:34"), null);

		verify(cacheManager).evictLocal("ExhibitionDetail", "12:34");
	}

	@Test
	@DisplayName("콜론이 없는 메시지는 예외 없이 L1 전체를 비운다 — 무엇을 못 지웠는지 몰라도 안전해진다")
	void onMessage_포맷이상_전체삭제() {
		assertThatCode(() -> listener.onMessage(메시지("콜론없음"), null)).doesNotThrowAnyException();

		verify(cacheManager).clearLocal();
		verify(cacheManager, never()).evictLocal(anyString(), anyString());
		verify(invalidationMetrics).receiveFailed();
	}

	@Test
	@DisplayName("처리 중 예외가 나도 밖으로 전파하지 않는다 — 한 건의 실패가 구독을 죽이면 틈 ③이 틈 ②가 된다")
	void onMessage_예외_비전파() {
		willThrow(new RuntimeException("boom")).given(cacheManager).evictLocal(anyString(), anyString());

		assertThatCode(() -> listener.onMessage(메시지("HomeBanners:ALL"), null)).doesNotThrowAnyException();

		verify(cacheManager).clearLocal();
		verify(invalidationMetrics).receiveFailed();
	}

	@Test
	@DisplayName("빈 메시지도 안전하게 전체 삭제로 떨어진다")
	void onMessage_빈메시지_전체삭제() {
		assertThatCode(() -> listener.onMessage(메시지(""), null)).doesNotThrowAnyException();

		verify(cacheManager).clearLocal();
	}

	@Test
	@DisplayName("구독이 확인되면 L1 전체를 비운다 — 공백 구간에 무엇을 놓쳤든 L2에서 다시 채운다")
	void onChannelSubscribed_전체삭제() {
		listener.onChannelSubscribed("cache-invalidation".getBytes(UTF_8), 1L);

		verify(cacheManager).clearLocal();
		verify(invalidationMetrics).resubscribed();
	}

	@Test
	@DisplayName("구독 해제는 로그만 남긴다 — 재구독 때 어차피 전체를 비우므로 여기서 지울 것이 없다")
	void onChannelUnsubscribed_삭제없음() {
		assertThatCode(() -> listener.onChannelUnsubscribed("cache-invalidation".getBytes(UTF_8), 0L))
				.doesNotThrowAnyException();

		verify(cacheManager, never()).clearLocal();
	}
}
