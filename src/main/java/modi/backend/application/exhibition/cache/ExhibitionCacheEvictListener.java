package modi.backend.application.exhibition.cache;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;
import modi.backend.support.cache.CacheManager;
import modi.backend.support.cache.MyCache;

/**
 * - 전시가 바뀐 뒤의 캐시 삭제(Cache-Aside의 쓰기 쪽)
 *   - 관리자 수정: Redis의 목록 7종과 그 전시의 상세 + 이 서버의 L1 목록
 *   - 수집 등록: Redis의 목록 7종(+ 이 서버의 L1 목록)
 *   - 다른 서버의 L1 목록은 지우지 않음 — L1 TTL(10분)로 만료됨
 *
 * - 커밋 확정 뒤에만 지움(AFTER_COMMIT)
 *   - 커밋 전에 지우면, 커밋 전의 다른 조회가 옛 값을 다시 적재해 삭제가 헛일이 됨
 *   - 롤백된 경우엔 DB가 그대로인데 멀쩡한 캐시만 날아감
 *
 * - 새 값을 넣지 않고 지우기만 함
 *   - 다음 조회가 DB에서 읽어 적재함(조회 시 적재)
 *   - 삭제가 실패해도 예외는 나가지 않고, 그 키는 Redis TTL(30분)로 만료됨
 */
@Component
@RequiredArgsConstructor
public class ExhibitionCacheEvictListener {

	private final CacheManager cacheManager;

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void handle(ExhibitionAdminUpdatedEvent event) {
		cacheManager.evict(ExhibitionCache.ExhibitionDetail.INSTANCE, String.valueOf(event.exhibitionId()));
		evictLists();
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void handle(ExhibitionRegisteredEvent event) {
		evictLists();
	}

	private void evictLists() {
		for (MyCache list : ExhibitionCache.LISTS) {
			cacheManager.evict(list, ExhibitionCache.ENTRY_KEY);
		}
	}
}
