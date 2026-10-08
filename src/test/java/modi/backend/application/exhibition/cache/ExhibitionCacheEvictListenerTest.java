package modi.backend.application.exhibition.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Method;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import modi.backend.support.cache.CacheManager;
import modi.backend.support.cache.MyCache;

/**
 * - 전시가 바뀐 뒤 무엇을 지우는지 고정
 *   - 관리자 수정: 그 전시의 상세 + 목록 7종
 *   - 수집 등록: 목록 7종(상세는 아직 캐시에 없음)
 *
 * - AFTER_COMMIT이라는 사실 자체를 테스트로 박아 둠
 *   - 커밋 전에 지우면 커밋 전의 다른 조회가 옛 값을 다시 적재해 삭제가 헛일이 됨
 *   - 어노테이션 한 글자로 바뀌는 성질이라 런타임 동작만으로는 회귀를 못 잡음
 */
@ExtendWith(MockitoExtension.class)
class ExhibitionCacheEvictListenerTest {

	@Mock
	private CacheManager cacheManager;

	@InjectMocks
	private ExhibitionCacheEvictListener listener;

	@Test
	@DisplayName("관리자 수정: 그 전시의 상세와 목록 7종을 지운다")
	void 관리자수정_상세와목록() {
		listener.handle(new ExhibitionAdminUpdatedEvent(42L));

		verify(cacheManager).evict(ExhibitionCache.ExhibitionDetail.INSTANCE, "42");
		for (MyCache list : ExhibitionCache.LISTS) {
			verify(cacheManager).evict(list, ExhibitionCache.ENTRY_KEY);
		}
	}

	@Test
	@DisplayName("수집 등록: 목록 7종을 지우고 상세는 건드리지 않는다")
	void 수집등록_목록만() {
		listener.handle(new ExhibitionRegisteredEvent(42L));

		for (MyCache list : ExhibitionCache.LISTS) {
			verify(cacheManager).evict(list, ExhibitionCache.ENTRY_KEY);
		}
		verify(cacheManager, never()).evict(eq(ExhibitionCache.ExhibitionDetail.INSTANCE), anyString());
	}

	@ParameterizedTest
	@ValueSource(classes = {ExhibitionAdminUpdatedEvent.class, ExhibitionRegisteredEvent.class})
	@DisplayName("커밋 확정 뒤에만 소비한다(AFTER_COMMIT)")
	void AFTER_COMMIT(Class<?> eventType) throws NoSuchMethodException {
		Method handle = ExhibitionCacheEvictListener.class.getMethod("handle", eventType);

		TransactionalEventListener annotation = handle.getAnnotation(TransactionalEventListener.class);

		assertThat(annotation).isNotNull();
		assertThat(annotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
	}
}
