package modi.backend.domain.exhibition.catalog;

import java.util.Map;

/**
 * 전시 조회수 누산기 포트(도메인 소유, 구현은 infra — DIP).
 *
 * <p><b>왜 카운터를 DB 밖으로 뺐나</b>: {@code exhibitions.our_view_count}에는 인덱스가 둘 걸려 있어
 * (인기순 정렬축) 상세 조회 1회마다 인덱스 두 개가 갱신된다. 조회가 몰리는 것은 인기 전시라
 * <b>가장 인기 있는 행에 쓰기가 집중</b>되고, 그 행은 곧 캐시 히트율이 가장 높아야 할 행이다.
 * 그래서 조회 경로에서는 누산만 하고, 반영은 배치가 모아서 한 번에 한다.
 *
 * <p><b>수거 프로토콜</b>은 세 동사로 이뤄진다. {@link #drain()}으로 원자적으로 가져오고,
 * DB 반영이 성공하면 {@link #discardDrained()}, 실패하면 {@link #restoreDrained()}로 되돌린다.
 * 되돌리기가 안전한 이유는 누산이 <b>덧셈</b>이라 그 사이에 들어온 조회와 교환법칙이 성립하기 때문이다.
 *
 * <p>누산기는 <b>소실을 허용</b>한다(운영 Redis는 비영속이다). 잃으면 그 창의 조회수가 빠질 뿐,
 * 진실의 원천은 여전히 {@code exhibitions.our_view_count}다.
 */
public interface ExhibitionViewCounter {

	/**
	 * 조회 1회를 누산하고 <b>마지막 수거 이후의 누적 델타</b>를 돌려준다.
	 * 누산기에 접근할 수 없으면 0을 돌려준다 — 조회수 때문에 상세 응답이 실패하지는 않는다.
	 */
	long increase(Long exhibitionId);

	/**
	 * 누산분을 <b>원자적으로</b> 수거한다(전시 id → 증가량). 수거 직후부터 새 창이 시작된다.
	 * 이미 다른 인스턴스가 가져갔거나 누산분이 없으면 빈 맵이다 — 그래서 배치가 두 대에서 돌아도
	 * 같은 델타가 두 번 반영되지 않는다.
	 */
	Map<Long, Long> drain();

	/** 수거분을 폐기한다. <b>DB 반영이 확정된 뒤에만</b> 부른다. */
	void discardDrained();

	/** 수거분을 누산기로 되돌린다. DB 반영에 실패했을 때 부른다(그 사이 들어온 조회와 합산된다). */
	void restoreDrained();
}
