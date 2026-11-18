package modi.backend.infra.bookmark;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;
import modi.backend.domain.bookmark.ExhibitionBookmark;
import modi.backend.domain.bookmark.ExhibitionBookmarkRepository;

/**
 * {@link ExhibitionBookmarkRepository} 어댑터(DIP). 토글은 (user, exhibition) 단건을 <b>잠가서</b> 로드해
 * Entity의 restore/delete로 멱등 처리한다 — soft-delete 행이 유니크 제약을 유지한 채 재활성된다.
 * 잠그는 이유는 "실제로 바뀌었는가"를 북마크 수(V62)가 믿기 때문이다. 잠그지 않으면 같은 해제 행을 동시에
 * 되살리는 요청들이 모두 "해제 상태"를 읽고 모두 "바뀌었다"고 답해 수가 여러 번 오른다
 * (잠금을 빼면 {@code ExhibitionPopularityTest}의 8동시 재등록이 3번 모두 실패했다).
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
	public boolean add(Long userId, Long exhibitionId) {
		Optional<ExhibitionBookmark> existing = jpaRepository.findForUpdate(userId, exhibitionId);
		if (existing.isEmpty()) {
			jpaRepository.save(ExhibitionBookmark.create(userId, exhibitionId));
			return true;
		}
		ExhibitionBookmark bookmark = existing.get();
		if (bookmark.isActive()) {
			return false;
		}
		bookmark.restore();
		jpaRepository.save(bookmark);
		return true;
	}

	@Override
	public boolean remove(Long userId, Long exhibitionId) {
		Optional<ExhibitionBookmark> existing = jpaRepository.findForUpdate(userId, exhibitionId)
				.filter(ExhibitionBookmark::isActive);
		existing.ifPresent(bookmark -> {
			bookmark.delete();
			jpaRepository.save(bookmark);
		});
		return existing.isPresent();
	}

	@Override
	public List<Long> removeAllOf(Long userId) {
		List<ExhibitionBookmark> active = jpaRepository.findActiveByUserIdForUpdate(userId);
		active.forEach(ExhibitionBookmark::delete);
		jpaRepository.saveAll(active);
		return active.stream().map(ExhibitionBookmark::getExhibitionId).toList();
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
