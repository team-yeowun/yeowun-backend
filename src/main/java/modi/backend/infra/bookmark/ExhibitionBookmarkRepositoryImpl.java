package modi.backend.infra.bookmark;

import java.util.Collection;
import java.util.List;
import java.util.Set;

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
}
