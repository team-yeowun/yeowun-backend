package modi.backend.domain.bookmark;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 전시 북마크 영속화 포트(도메인 소유, DIP). 구현은 infra.
 * 조회는 모두 살아있는(해제되지 않은) 북마크만 본다.
 */
public interface ExhibitionBookmarkRepository {

	/** 해당 사용자가 전시를 현재 관심 등록했는지. */
	boolean existsActive(Long userId, Long exhibitionId);

	/** 관심 등록(멱등) — 없으면 생성, soft-delete 상태면 복원, 이미 활성이면 무변경. */
	void add(Long userId, Long exhibitionId);

	/** 관심 해제(멱등) — 활성이면 soft-delete, 이미 없으면 무변경. */
	void remove(Long userId, Long exhibitionId);

	/** 사용자의 활성 관심 전시 수(프로필 stats.bookmarkCount용). */
	long countByUserId(Long userId);

	/** 주어진 전시 id들 중 사용자가 관심 등록한 id 집합(목록의 bookmarked 필드 일괄 주입용). userId null이면 빈 집합. */
	Set<Long> findBookmarkedExhibitionIds(Long userId, Collection<Long> exhibitionIds);

	/** 사용자의 활성 관심 전시 id를 등록 최신순(createdAt desc, id desc)으로 반환(전시 컬럼 정렬·알림 선별의 IN 대상용). */
	List<Long> findActiveExhibitionIdsByUserIdOrderByRegisteredDesc(Long userId);

	/**
	 * 등록 최신순 <b>한 페이지</b>의 전시 id. 정렬 키가 북마크 테이블 자기 컬럼이라 여기서 잘라낼 수 있다 —
	 * 전량을 읽어 앱에서 자르지 않는다.
	 *
	 * @param cursorExhibitionId 커서로 받은 마지막 전시 id. null이면 첫 페이지.
	 *                           해당 북마크가 그새 해제됐다면 첫 페이지로 폴백한다.
	 */
	List<Long> findActiveExhibitionIdsPage(Long userId, Long cursorExhibitionId, int limit);
}
