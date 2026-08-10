package modi.backend.application.exhibition;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * - {@code withBookmarked}가 관심 여부 하나만 바꾸는지 고정
 *   - 필드 13개를 위치 인자로 다시 나열하는 구조라 누락·순서 뒤바뀜이 조용히 통과할 수 있음
 *   - 특히 인접한 String 필드(place·region·category)는 바뀌어도 컴파일이 통과
 *   - 그래서 필드마다 서로 다른 값을 넣고 통째로 비교
 */
class ExhibitionResultListItemTest {

	private static final LocalDate 시작일 = LocalDate.of(2026, 3, 1);
	private static final LocalDate 종료일 = LocalDate.of(2026, 4, 30);

	private static ExhibitionResult.ListItem 항목(boolean bookmarked) {
		return new ExhibitionResult.ListItem(7L, "CATALOG", "전시명", "poster-url", 시작일, 종료일,
				"전시장명", "SEOUL", "ART", "작가 요약", 12, true, bookmarked);
	}

	@Test
	@DisplayName("관심 여부만 바뀌고 나머지 필드는 그대로다")
	void withBookmarked_관심여부만_교체() {
		assertThat(항목(false).withBookmarked(true)).isEqualTo(항목(true));
	}

	@Test
	@DisplayName("같은 값으로 덮어써도 원본과 같다")
	void withBookmarked_같은값_원본유지() {
		assertThat(항목(true).withBookmarked(true)).isEqualTo(항목(true));
	}

	@Test
	@DisplayName("관심 여부를 내릴 수도 있다 — 캐시에 굳은 true를 비로그인 요청이 지운다")
	void withBookmarked_해제() {
		assertThat(항목(true).withBookmarked(false)).isEqualTo(항목(false));
	}
}
