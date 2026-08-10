package modi.backend.application.exhibition;

/**
 * - 전시 목록의 페이지 크기 정책
 *   - 기본값·상한과 요청 값을 실제 크기로 접는 규칙이 여기 하나로 모임
 *
 * - 상수를 서비스에서 빼낸 이유
 *   - 캐시 판정({@code ExhibitionListCacheResolver})은 "이 요청이 기본 페이지인가"를 알아야 함
 *   - 목록 조회({@code ExhibitionListService})는 "이 요청을 몇 건으로 자를 것인가"를 알아야 함
 *   - 같은 숫자를 양쪽이 각자 들고 있으면 한쪽만 바뀌는 순간 캐시가 다른 크기의 페이지를 서빙
 *   - 예: 20으로 캐시된 값을 30 요청에 돌려줌
 *   - 주석으로 "같아야 한다"고 적는 대신 출처를 하나로 만듦
 */
public final class ExhibitionPageSize {

	/** 크기 미지정 시의 페이지 크기. */
	public static final int DEFAULT = 20;

	/** 한 번에 내줄 수 있는 최대 건수. */
	public static final int MAX = 50;

	private ExhibitionPageSize() {
	}

	/**
	 * - 요청 값이 실제로 접히는 크기
	 *   - null·1 미만 → {@link #DEFAULT}
	 *   - 상한 초과 → {@link #MAX}
	 */
	public static int clamp(Integer size) {
		if (size == null || size < 1) {
			return DEFAULT;
		}
		return Math.min(size, MAX);
	}

	/**
	 * - 접고 나서 기본 크기가 되는가
	 *   - 원본 값이 아니라 접힌 값을 봄
	 *   - {@code size=0}은 목록이 어차피 20건을 내주므로 기본 페이지와 결과가 같음
	 *   - 따라서 같은 캐시를 태워도 됨
	 */
	public static boolean isDefault(Integer size) {
		return clamp(size) == DEFAULT;
	}
}
