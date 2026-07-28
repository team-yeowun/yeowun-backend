package modi.backend.application.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import modi.backend.TestcontainersConfiguration;
import modi.backend.ingestion.domain.port.ExhibitionCatalogClient;
import modi.backend.support.error.CoreException;
import modi.backend.support.error.ErrorType;

/**
 * 검색 기록 유스케이스 검증(@SpringBootTest + Testcontainers-MySQL).
 *
 * <p>이 기능의 값은 "최근 검색어 10개가 쓸모 있게 유지되는가"에 있다. 그래서 <b>중복이 하나로 합쳐지는지</b>와
 * <b>10개를 넘기면 오래된 것이 정리되는지</b>를 못박는다 — 둘 중 하나만 빠져도 목록이 금방 무의미해진다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.exhibition.enrich.scheduling-enabled=false")
class SearchHistoryFacadeTest {

	private static final AtomicInteger USER = new AtomicInteger(5_000);

	@Autowired
	SearchHistoryFacade searchHistoryFacade;

	@MockitoBean
	ExhibitionCatalogClient exhibitionCatalogClient;

	@Test
	@DisplayName("기록한 검색어가 최신순으로 나온다")
	void 최근_검색어를_최신순으로_반환한다() {
		Long userId = nextUser();
		기록(userId, "김환기", "리움", "이불");

		assertThat(keywordsOf(userId)).containsExactly("이불", "리움", "김환기");
	}

	@Test
	@DisplayName("같은 검색어를 다시 치면 새 항목이 생기지 않고 맨 위로 올라온다")
	void 같은_검색어는_합쳐지고_맨_위로_온다() {
		Long userId = nextUser();
		기록(userId, "김환기", "리움", "이불");

		기록(userId, "김환기");

		// 중복이 쌓이면 "최근 10개"가 같은 단어로 채워진다 — 항목 수는 그대로여야 한다.
		assertThat(keywordsOf(userId)).containsExactly("김환기", "이불", "리움");
	}

	@Test
	@DisplayName("10개를 넘기면 가장 오래된 기록이 정리된다")
	void 열개를_넘기면_오래된_것이_정리된다() {
		Long userId = nextUser();
		for (int i = 1; i <= 12; i++) {
			기록(userId, "검색어" + i);
		}

		var keywords = keywordsOf(userId);

		assertThat(keywords).hasSize(10);
		assertThat(keywords).startsWith("검색어12", "검색어11");
		// 조회에서만 자르면 행은 계속 쌓인다 — 저장 시점 정리가 동작해야 한다.
		assertThat(keywords).doesNotContain("검색어1", "검색어2");
	}

	@Test
	@DisplayName("검색어 앞뒤·연속 공백은 정리해 같은 기록으로 본다")
	void 공백은_정규화한다() {
		Long userId = nextUser();

		기록(userId, "  김  환기 ");
		기록(userId, "김 환기");

		assertThat(keywordsOf(userId)).containsExactly("김 환기");
	}

	@Test
	@DisplayName("2글자 미만 검색어는 400 — 검색 API와 같은 규칙")
	void 두글자_미만은_거부한다() {
		Long userId = nextUser();

		assertThatThrownBy(() -> 기록(userId, "김"))
				.isInstanceOf(CoreException.class)
				.extracting(e -> ((CoreException) e).errorCode())
				.isEqualTo(ErrorType.INVALID_INPUT);
	}

	@Test
	@DisplayName("개별 삭제는 멱등이고, 타인의 기록은 403")
	void 개별_삭제는_소유자만_가능하다() {
		Long owner = nextUser();
		Long other = nextUser();
		기록(owner, "김환기");
		Long historyId = searchHistoryFacade.getRecent(owner).content().get(0).searchHistoryId();

		assertThatThrownBy(() -> searchHistoryFacade.delete(new SearchHistoryCriteria.Delete(other, historyId)))
				.isInstanceOf(CoreException.class)
				.extracting(e -> ((CoreException) e).errorCode())
				.isEqualTo(ErrorType.FORBIDDEN);

		searchHistoryFacade.delete(new SearchHistoryCriteria.Delete(owner, historyId));
		assertThat(keywordsOf(owner)).isEmpty();

		// 이미 없는 기록을 다시 지워도 조용히 넘어간다(멱등).
		searchHistoryFacade.delete(new SearchHistoryCriteria.Delete(owner, historyId));
	}

	@Test
	@DisplayName("전체 삭제는 내 기록만 지운다")
	void 전체_삭제는_내_것만_지운다() {
		Long mine = nextUser();
		Long others = nextUser();
		기록(mine, "김환기", "리움");
		기록(others, "이불");

		long deleted = searchHistoryFacade.deleteAll(mine);

		assertThat(deleted).isEqualTo(2);
		assertThat(keywordsOf(mine)).isEmpty();
		assertThat(keywordsOf(others)).containsExactly("이불");
	}

	// ── 헬퍼 ────────────────────────────────────────────────────────────────

	private Long nextUser() {
		return (long) USER.getAndIncrement();
	}

	private void 기록(Long userId, String... keywords) {
		for (String keyword : keywords) {
			searchHistoryFacade.record(new SearchHistoryCriteria.Record(userId, keyword));
		}
	}

	private java.util.List<String> keywordsOf(Long userId) {
		return searchHistoryFacade.getRecent(userId).content().stream()
				.map(SearchHistoryResult.Item::keyword)
				.toList();
	}
}
