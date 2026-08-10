package modi.backend.application.exhibition.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Method;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import modi.backend.support.cache.CacheManager;

/**
 * - 관리자 수정 이벤트가 상세 캐시를 지우는지 고정
 *
 * - AFTER_COMMIT이라는 사실 자체를 테스트로 박아 둠
 *   - 커밋 전에 지우면 롤백된 경우 DB는 옛 값인데 전 서버의 멀쩡한 캐시만 날아감
 *   - 게다가 그 뒤 조회가 옛 값을 다시 올려 무효화가 헛일이 됨
 *   - 어노테이션 한 글자로 바뀌는 성질이라 런타임 동작만으로는 회귀를 못 잡음
 */
@ExtendWith(MockitoExtension.class)
class ExhibitionCacheEvictListenerTest {

	@Mock
	private CacheManager cacheManager;

	@InjectMocks
	private ExhibitionCacheEvictListener listener;

	@Test
	@DisplayName("수정된 전시의 상세 캐시를 지운다")
	void handle_상세캐시_evict() {
		listener.handle(new ExhibitionAdminUpdatedEvent(42L));

		verify(cacheManager).evict(ExhibitionCache.ExhibitionDetail.INSTANCE, "42");
	}

	@Test
	@DisplayName("커밋 확정 뒤에만 소비한다(AFTER_COMMIT)")
	void handle_AFTER_COMMIT() throws NoSuchMethodException {
		Method handle = ExhibitionCacheEvictListener.class.getMethod("handle", ExhibitionAdminUpdatedEvent.class);

		TransactionalEventListener annotation = handle.getAnnotation(TransactionalEventListener.class);

		assertThat(annotation).isNotNull();
		assertThat(annotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
	}
}
