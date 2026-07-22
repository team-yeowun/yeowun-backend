package modi.backend.ingestion.infra.ai;

import static org.assertj.core.api.Assertions.assertThat;

import modi.backend.ingestion.infra.ai.GeminiClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.micrometer.observation.ObservationRegistry;
import modi.backend.domain.exhibition.genre.GenreClassification;
import modi.backend.domain.exhibition.genre.GenreClassificationRequest;
import modi.backend.domain.exhibition.genre.GenreInstruction;
import modi.backend.domain.exhibition.genre.GenreKeyword;
import modi.backend.domain.exhibition.genre.GenreProvider;
import modi.backend.domain.exhibition.genre.GenreResult;
import modi.backend.ingestion.config.AiModelConfig;
import modi.backend.ingestion.properties.GeminiProperties;

/**
 * {@link GeminiClient} — <b>실제 Gemini API를 호출하는 수동 확인용</b> 테스트.
 *
 * <p><b>자동 실행 대상이 아니다.</b> {@code @Tag("manual")}이라 {@code ./gradlew test}(CI 포함)에서 제외된다.
 * 돌리는 방법은 둘 — {@code ./gradlew manualTest} 또는 IDE에서 이 클래스·메서드 직접 실행.
 * 인증키는 환경변수 {@code GEMINI_API_KEY}가 필요하다(없으면 호출 없이 통과시킨다 — CI·키 없는 로컬 보호).
 *
 * <p><b>여기서만 증명되는 것</b>: Gemini가 {@code responseMimeType=text/x.enum}과 <b>JSON Schema</b>
 * ({@code {"type":"string","enum":[...]}})의 조합을 실제로 받아주는가.
 * Spring AI는 {@code GoogleGenAiChatOptions.responseSchema}(String)를 google-genai SDK의
 * {@code responseJsonSchema}로 넘긴다 — Gemini 고유의 {@code responseSchema}(type STRING + enum)와
 * <b>다른 필드</b>다. 목 서버는 "요청이 그렇게 나간다"까지만 증명하므로 수용 여부는 실호출로만 확인된다.
 *
 * <p>여기서 거부되면(400 등) 폴백은 {@code application/json} + 같은 enum 스키마다 — 응답이 따옴표 붙은
 * JSON 문자열로 오니 그것만 벗기면 되고, <b>스키마 수준의 enum 강제는 그대로 유지</b>된다.
 *
 * <p>조립은 운영과 같게 한다(연결 AiModelConfig + 어댑터가 옵션) — 별도 배선을 만들지 않아야 운영과
 * 틀어졌을 때 여기서 먼저 드러난다. 응답 포맷·예외 계약의 세부는 MockWebServer 기반 {@link GeminiClientTest}가 본다.
 * 호출 한도를 아끼려 1콜로 제한한다.
 */
@Tag("manual")
class GeminiClientManualTest {

	private final GenreClassification subject = new GenreClassification(
			"모네에서 세잔까지 — 인상주의 특별전", null, "인상주의 대표작을 모은 특별전", "예술의전당 한가람미술관", null, "전시");

	@Test
	@DisplayName("실제 Gemini가 text/x.enum + JSON Schema enum 강제를 받아들이고, 마스터 장르 하나를 돌려준다")
	void classify_realGemini_returnsMasterGenre() {
		String apiKey = System.getenv("GEMINI_API_KEY");
		if (apiKey == null || apiKey.isBlank()) {
			System.out.println("[manual] GEMINI_API_KEY 없음 — 실호출 건너뜀");
			return;
		}

		GeminiProperties properties = new GeminiProperties(null, apiKey, "gemini-2.5-flash", 30L);
		GeminiClient classifier = new GeminiClient(
				new AiModelConfig().genreGeminiChatModel(properties, ObservationRegistry.NOOP), properties);

		GenreResult result = classifier.classify(new GenreClassificationRequest(
				GenreInstruction.STANDARD, GenreKeyword.all(), subject.toPromptText()));

		System.out.println("[manual] 장르=" + result.genreKeyword() + " 실서빙모델=" + result.model());
		assertThat(GenreKeyword.contains(result.genreKeyword())).isTrue();
		assertThat(result.provider()).isEqualTo(GenreProvider.GEMINI);
		// 계보엔 요청 모델(별칭일 수 있다)이 아니라 응답이 말한 실서빙 모델이 남아야 한다.
		assertThat(result.model()).isNotBlank();
	}
}
