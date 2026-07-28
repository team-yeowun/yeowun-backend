package modi.backend.infra.bookmark;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;
import modi.backend.domain.bookmark.ExhibitionBookmark;
import modi.backend.domain.bookmark.ExhibitionBookmarkRepository;

/**
 * {@link ExhibitionBookmarkRepository} 어댑터(DIP). 토글은 (user, exhibition) 단건을 로드해
 * Entity의 restore/delete로 멱등 처리한다 — soft-delete 행이 유니크 제약을 유지한 채 재활성된다.
 */
@Repository
@RequiredArgsConstructor
public class ExhibitionBookmarkRepositoryImpl implements ExhibitionBookmarkRepository {

	private final ExhibitionBookmarkJpaRepository jpaRepository;

	@Override
	public boolean existsActive(Long userId, Long exhibitionId) {
		return jpaRepository.existsByUserIdAndExhibitionIdAndDeletedAtIsNull(userId, exhibitionId);
	}

	@Override
	public void add(Long userId, Long exhibitionId) {
		jpaRepository.findByUserIdAndExhibitionId(userId, exhibitionId)
				.ifPresentOrElse(
						bookmark -> {
							bookmark.restore();
							jpaRepository.save(bookmark);
						},
						() -> jpaRepository.save(ExhibitionBookmark.create(userId, exhibitionId)));
	}

	@Override
	public void remove(Long userId, Long exhibitionId) {
		jpaRepository.findByUserIdAndExhibitionId(userId, exhibitionId)
				.ifPresent(bookmark -> {
					bookmark.delete();
					jpaRepository.save(bookmark);
				});
	}

	@Override
	public long countByUserId(Long userId) {
		return jpaRepository.countByUserIdAndDeletedAtIsNull(userId);
	}

	@Override
	public Set<Long> findBookmarkedExhibitionIds(Long userId, Collection<Long> exhibitionIds) {
		if (userId == null || exhibitionIds == null || exhibitionIds.isEmpty()) {
			return Set.of();
		}
		return Set.copyOf(jpaRepository.findActiveExhibitionIdsIn(userId, exhibitionIds));
	}

	@Override
	public List<Long> findActiveExhibitionIdsByUserIdOrderByRegisteredDesc(Long userId) {
		return jpaRepository.findActiveExhibitionIdsOrderByRegisteredDesc(userId);
	}

	@Override
	public List<Long> findActiveExhibitionIdsPage(Long userId, Long cursorExhibitionId, int limit) {
		Pageable page = PageRequest.of(0, Math.max(1, limit));
		if (cursorExhibitionId == null) {
			return jpaRepository.findActiveExhibitionIdsFirstPage(userId, page);
		}
		// 커서 행의 (createdAt, id)를 기준으로 그 뒤만 읽는다. 그새 관심 해제됐다면 기준이 없으므로
		// 첫 페이지로 폴백한다(중복이 보일지언정 목록이 비어 보이지 않게 — 기존 인메모리 동작과 같다).
		return jpaRepository.findByUserIdAndExhibitionId(userId, cursorExhibitionId)
				.filter(ExhibitionBookmark::isActive)
				.map(b -> jpaRepository.findActiveExhibitionIdsAfter(userId, b.getCreatedAt(), b.getId(), page))
				.orElseGet(() -> jpaRepository.findActiveExhibitionIdsFirstPage(userId, page));
	}
}
