package modi.backend.ingestion.infra.google;

import modi.backend.ingestion.infra.gemini.GeminiApi;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.PostExchange;

/**
 * 구글 Places(New) Text Search 선언형 클라이언트(HTTP Interface, RestClient 백엔드).
 * 기존 패턴({@code GeminiApi})과 동일하게 REST를 선언한다.
 * <ul>
 *   <li>인증키는 URL 노출을 피해 {@code X-Goog-Api-Key} 헤더로 전달.</li>
 *   <li>{@code X-Goog-FieldMask}로 받을 필드만 지정(New API 필수 — 없으면 400).</li>
 * </ul>
 */
public interface GoogleMapsApi {

	@PostExchange("/v1/places:searchText")
	GoogleMapsDto.SearchTextResponse searchText(
			@RequestHeader("X-Goog-Api-Key") String apiKey,
			@RequestHeader("X-Goog-FieldMask") String fieldMask,
			@RequestBody GoogleMapsDto.SearchTextRequest request);
}
