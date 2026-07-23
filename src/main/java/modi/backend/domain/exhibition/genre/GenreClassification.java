package modi.backend.domain.exhibition.genre;

import modi.backend.domain.exhibition.genre.GenreClassifier;

import java.util.Optional;

/**
 * 장르 분류 입력(도메인 값). 전시의 분류 근거가 되는 텍스트 필드만 담는다 — CATALOG(공공데이터)·CUSTOM(사용자 등록) 공통.
 * 분류기({@link GenreClassifier})가 provider 무관하게 소비하도록 순수 자바 값만 노출한다.
 * 대부분 nullable(원천 결측 잦음) — {@link #toPromptText()}가 값 있는 필드만 이어붙인다.
 */
public record GenreClassification(String title, String categoryHint, String description,
		String place, String artist, String realmName) {

	/**
	 * LLM에 넘길 전시 요약 텍스트. 값이 있는 필드만 "라벨: 값" 한 줄씩 이어붙인다(빈 입력이면 제목만/공백).
	 * 사람이 읽는 참고 자료이지 지시가 아니다 — 프롬프트 주입 방지 가드는 호출부(시스템 프롬프트)가 담당한다.
	 */
	public String toPromptText() {
		StringBuilder sb = new StringBuilder();
		append(sb, "제목", title);
		append(sb, "장소", place);
		append(sb, "분야", realmName);
		append(sb, "카테고리", categoryHint);
		append(sb, "작가", artist);
		append(sb, "설명", description);
		return sb.toString().trim();
	}

	private static void append(StringBuilder sb, String label, String value) {
		String trimmed = Optional.ofNullable(value).map(String::trim).orElse("");
		if (!trimmed.isEmpty()) {
			sb.append(label).append(": ").append(trimmed).append('\n');
		}
	}
}
