package modi.backend.ingestion.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 전시 영업시간(구글 Places New) 조회 설정. {@code app.exhibition.place-hours.*} 바인딩.
 * <p>
 * provider: 주 조회기 선택({@code google} | {@code mock}) — 두 구현이 빈으로 공존하고 이 값으로 하나가 @Primary로 선택된다.
 * <b>기본 mock</b>(로컬·CI·develop 유료호출 0). 운영(main)만 {@code google} + api-key 주입.
 * api-key는 시크릿(GOOGLE_MAPS_API_KEY) 주입 — provider=google이라도 키가 비면 mock로 폴백한다({@link #useGoogle()}).
 * 재검증 폐기(설계 D4)로 refresh-after-days·max-venues-per-run은 삭제됐다 — 조회는 신규 전시장 초기화 1회뿐이다.
 */
@ConfigurationProperties(prefix = "app.exhibition.place-hours")
public record GooglePlaceProperties(String provider, String baseUrl, String apiKey, String languageCode,
                                    String regionCode, Long timeoutSeconds) {

	public GooglePlaceProperties {
		if (provider == null || provider.isBlank()) {
			provider = "mock";
		}
		if (baseUrl == null || baseUrl.isBlank()) {
			baseUrl = "https://places.googleapis.com";
		}
		if (languageCode == null || languageCode.isBlank()) {
			languageCode = "ko";
		}
		if (regionCode == null || regionCode.isBlank()) {
			regionCode = "KR";
		}
		if (timeoutSeconds == null || timeoutSeconds <= 0) {
			timeoutSeconds = 10L;
		}
	}

	/** api-key가 실제로 채워졌는지(빈 문자열 = 미설정). */
	public boolean hasApiKey() {
		return apiKey != null && !apiKey.isBlank();
	}

	/** 실호출(구글)을 쓸지 — provider=google 이고 키가 있을 때만. 그 외(키 없음 포함)는 mock. */
	public boolean useGoogle() {
		return "google".equalsIgnoreCase(provider) && hasApiKey();
	}
}
