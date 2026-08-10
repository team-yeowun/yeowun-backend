package modi.backend.application.exhibition;

import java.time.LocalDate;

/**
 * 전시 유스케이스 입력 모음. (Request →[Controller] Criteria → Facade)
 * region·category·section은 원시 문자열로 받아 Facade에서 enum 변환·검증한다(미정의 코드 → INVALID_INPUT).
 * requesterId는 인증(옵션)에서 채운다 — 비로그인이면 null.
 */
public final class ExhibitionCriteria {

	private ExhibitionCriteria() {
	}

	/**
	 * 목록/탐색 조회 입력(커서 페이지네이션).
	 *
	 * @param keyword  전시명·전시장명 부분 일치(최소 2글자, 1글자 400)
	 * @param section  섹션 코드(ending-soon|opening-this-month|free). 미정의 400.
	 * @param period   opening-this-month용(month 기본|week)
	 * @param region   지역 코드 콤마 다중(SEOUL,GYEONGGI)
	 * @param category 카테고리 코드 콤마 다중
	 * @param date     해당일 진행 전시(YYYY-MM-DD, LocalDate)
	 * @param sort     정렬 코드(latest 기본|ending|popular|distance)
	 * @param lat,lng  sort=distance 필수 좌표
	 * @param cursor   opaque 커서(첫 페이지는 null)
	 * @param size     페이지 크기(기본 20, 최대 50)
	 */
	public record Search(String keyword, String section, String period, String region, String category,
			LocalDate date, String sort, Double lat, Double lng, String cursor, Integer size, Long requesterId) {

		/**
		 * - 필터도 커서도 없이 섹션·정렬만 남은 기본 크기 첫 페이지인가
		 *   - 홈·탐색의 첫 화면 요청
		 *
		 * - 판정을 이 record가 직접 하는 이유
		 *   - 필드가 늘어날 때 판정도 함께 늘어나야 함
		 *   - 바깥에서 필드를 하나씩 꺼내 null인지 세는 방식은 그 보장이 없음
		 *   - 아홉 번째 필터가 생겨도 그 코드는 아무 말 없이 예전 답을 계속 냄
		 *
		 * - 그래서 세는 대신 허용된 필드만 남긴 쌍둥이를 만들어 자기 자신과 비교
		 *   - record의 {@code equals}는 전 필드를 봄
		 *   - 새 필터 필드가 추가되면 쌍둥이 쪽은 null이라 값이 실린 요청은 자동으로 걸러짐
		 *   - 깜빡했을 때 틀린 캐시를 주는 쪽이 아니라 캐시를 안 타는 쪽으로 실패
		 */
		public boolean isPlainFirstPage() {
			return ExhibitionPageSize.isDefault(size)
					&& equals(new Search(null, section, null, null, null, null, sort, null, null, null, size,
							requesterId));
		}
	}

	/** 전시 상세 조회 입력. requesterId는 CUSTOM 접근 권한 판단 + 개인화(bookmarked/recorded)용. */
	public record Detail(Long exhibitionId, Long requesterId) {
	}

	/**
	 * 개인 전시(CUSTOM) 등록 입력. ownerId는 인증에서 채운다. format은 전시 형태 코드(원시 문자열, Facade에서 enum 변환).
	 * venueId·place는 둘 중 하나 — venueId가 있으면 전시관에서 장소·지역을 파생하고 place는 무시한다.
	 */
	public record CustomCreate(Long ownerId, String title, Long venueId, String place, LocalDate startDate,
			LocalDate endDate, String region, String category, String format, String artist, String posterUrl,
			String genreKeyword) {
	}
}
