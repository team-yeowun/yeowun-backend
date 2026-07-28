package modi.backend.domain.search;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import modi.backend.support.error.CoreException;
import modi.backend.support.error.ErrorType;

/**
 * 전시 검색 기록(최근 검색어). 회원 전용이며 {@code (user_id, keyword)} 한 쌍당 한 행이다.
 *
 * <p><b>같은 검색어를 다시 치면 새 행이 아니라 {@link #searchedAgain} 으로 시각만 갱신</b>한다 —
 * 중복을 허용하면 "최근 10개"가 같은 단어로 채워져 목록이 무의미해진다.
 *
 * <p>{@code BaseEntity}를 쓰지 않는다: 검색 기록은 감사 대상이 아니고 사용자가 "지웠다"고 기대하는 데이터라
 * <b>hard delete</b>가 맞다. soft delete면 유니크 제약 탓에 재검색 시 되살리는 분기가 생긴다.
 */
@Entity
@Table(name = "search_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SearchHistory {

	/** 검색 API와 같은 규칙 — 검색은 되는데 기록만 안 되는(또는 그 반대) 상황을 만들지 않는다. */
	private static final int MIN_KEYWORD_LENGTH = 2;
	private static final int MAX_KEYWORD_LENGTH = 100;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "keyword", nullable = false, length = MAX_KEYWORD_LENGTH)
	private String keyword;

	@Column(name = "searched_at", nullable = false)
	private LocalDateTime searchedAt;

	private SearchHistory(Long userId, String keyword, LocalDateTime searchedAt) {
		this.userId = userId;
		this.keyword = keyword;
		this.searchedAt = searchedAt;
	}

	/** 처음 검색한 키워드를 기록한다. 키워드 정규화·검증은 {@link #normalize}가 맡는다. */
	public static SearchHistory create(Long userId, String keyword, LocalDateTime searchedAt) {
		return new SearchHistory(userId, normalize(keyword), searchedAt);
	}

	/** 같은 키워드를 다시 검색했다 — 시각만 앞당겨 목록 맨 위로 올린다. */
	public void searchedAgain(LocalDateTime searchedAt) {
		this.searchedAt = searchedAt;
	}

	/** 이 기록이 해당 사용자의 것인가(삭제 권한 판정). */
	public boolean isOwnedBy(Long userId) {
		return this.userId.equals(userId);
	}

	/**
	 * 저장·조회에 쓸 정규화 키워드. 앞뒤 공백을 없애고 연속 공백을 하나로 줄인다 —
	 * {@code "김 환기"}와 {@code "김  환기"}가 다른 기록으로 남지 않게 한다.
	 */
	public static String normalize(String raw) {
		if (raw == null || raw.isBlank()) {
			throw new CoreException(ErrorType.INVALID_INPUT, "검색어가 비어 있습니다.");
		}
		String normalized = raw.trim().replaceAll("\\s+", " ");
		if (normalized.length() < MIN_KEYWORD_LENGTH) {
			throw new CoreException(ErrorType.INVALID_INPUT, "검색어는 최소 " + MIN_KEYWORD_LENGTH + "글자여야 합니다: " + raw);
		}
		if (normalized.length() > MAX_KEYWORD_LENGTH) {
			throw new CoreException(ErrorType.INVALID_INPUT, "검색어는 " + MAX_KEYWORD_LENGTH + "글자를 넘을 수 없습니다.");
		}
		return normalized;
	}
}
