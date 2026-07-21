package modi.backend.ingestion.infra.gemini;


import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.PostExchange;

/**
 * Gemini Generative Language API generateContent 선언형 클라이언트(HTTP Interface, RestClient 백엔드).
 * 프로젝트 기존 패턴({@code KakaoApi})과 동일하게 {@code @HttpExchange}로 REST를 선언한다.
 * 인증키는 URL 노출을 피해 {@code x-goog-api-key} 헤더로 전달한다.
 * 무료 한도 초과 시 API가 429를 반환하며, RestClient는 이를 {@code HttpClientErrorException.TooManyRequests}로 던진다
 * — 재시도·폴백은 {@link GeminiGenreClassifier}가 처리한다.
 */
public interface GeminiApi {

	@PostExchange("/v1beta/models/{model}:generateContent")
	GeminiDto.Response generateContent(@PathVariable String model,
			@RequestHeader("x-goog-api-key") String apiKey,
			@RequestBody GeminiDto.Request request);
}
