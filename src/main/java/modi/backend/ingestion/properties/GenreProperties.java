package modi.backend.ingestion.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 전시 장르 분류 설정. {@code app.exhibition.genre.*} 바인딩.
 * classifier: 주 분류기 선택({@code gemini} | {@code mock}) — 구현들이 빈으로 공존하고 이 값으로 하나가 선택된다.
 * {@code gemini}는 폴백 체인(1차 Gemini → 2차 Claude, ADR-11), 그 외는 결정적 mock(로컬/CI 기본 — AI 호출 0).
 * (장르 생성 시점·배치 상한은 {@link CatalogEnrichProperties} — 동기화 직후 신규분만 분류한다.)
 */
@ConfigurationProperties(prefix = "app.exhibition.genre")
public record GenreProperties(String classifier) {

	public GenreProperties {
		if (classifier == null || classifier.isBlank()) {
			classifier = "gemini";
		}
	}

	/** 주 분류기로 AI 체인(1차 Gemini)을 쓰는지. 그 외 값은 mock으로 취급. */
	public boolean useGemini() {
		return "gemini".equalsIgnoreCase(classifier);
	}
}
