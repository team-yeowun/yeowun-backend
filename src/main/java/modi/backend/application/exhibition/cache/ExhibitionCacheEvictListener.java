package modi.backend.application.exhibition.cache;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;
import modi.backend.support.cache.CacheManager;

/**
 * - 전시 수정 → 상세 캐시 무효화
 *   - {@code evict} 하나가 L2 삭제 + 자기 L1 삭제 + 방송을 함께 함
 *
 * - 커밋 확정 뒤에만 지움(AFTER_COMMIT)
 *   - 커밋 전에 지우면 롤백된 경우 DB는 옛 값 그대로인데 전 서버의 멀쩡한 캐시만 날아감
 *   - 지워진 뒤 다음 조회가 옛 값을 다시 캐시에 올려 무효화가 헛일이 됨
 */
@Component
@RequiredArgsConstructor
public class ExhibitionCacheEvictListener {

	private final CacheManager cacheManager;

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void handle(ExhibitionAdminUpdatedEvent event) {
		cacheManager.evict(ExhibitionCache.ExhibitionDetail.INSTANCE, String.valueOf(event.exhibitionId()));
	}
}
