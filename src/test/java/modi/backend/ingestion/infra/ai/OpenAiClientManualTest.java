package modi.backend.ingestion.infra.ai;

import static org.assertj.core.api.Assertions.assertThat;

import modi.backend.ingestion.infra.ai.OpenAiClient;
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
import modi.backend.ingestion.properties.GenreOpenAiProperties;

/**
 * {@link OpenAiClient} — <b>실제 OpenAI API를 호출하는 수동 확인용</b> 테스트(1차 Gemini 러너와 같은 방식).
 *
 * <p><b>자동 실행 대상이 아니다.</b> {@code @Tag("manual")}이라 {@code ./gradlew test}(CI 포함)에서 제외된다.
 * 돌리는 방법: {@code OPENAPI_KEY=... ./gradlew manualTest --tests '*OpenAiClientManualTest'}.
 * 키가 없으면 호출 없이 건너뛴다(CI·키 없는 로컬 보호).
 *
 * <p><b>여기서만 증명되는 것</b>: 실제 모델이 {@code json_schema}(strict) 구조화 출력을 받아들이고
 * 허용 집합 안의 값을 돌려주는가, 그리고 응답 {@code model}이 계보에 쓸 만한 값인가.
 * 목 서버는 "요청이 그렇게 나간다"까지만 증명한다.
 */
@Tag("manual")
class OpenAiClientManualTest {

	private final GenreClassification subject = new GenreClassification(
			"모네에서 세잔까지 — 인상주의 특별전", null, "인상주의 대표작을 모은 특별전", "예술의전당 한가람미술관", null, "전시");

	@Test
	@DisplayName("실제 OpenAI가 구조화 출력으로 마스터 장르 하나를 돌려준다")
	void classify_realOpenAi_returnsMasterGenre() {
		String apiKey = System.getenv("OPENAPI_KEY");
		if (apiKey == null || apiKey.isBlank()) {
			System.out.println("[manual] OPENAPI_KEY 없음 — 실호출 건너뜀");
			return;
		}

		GenreOpenAiProperties properties = new GenreOpenAiProperties(null, apiKey, null, 30L);
		OpenAiClient classifier = new OpenAiClient(
				new AiModelConfig().genreOpenAiChatModel(properties, ObservationRegistry.NOOP), properties);

		GenreResult result = classifier.classify(new GenreClassificationRequest(
				GenreInstruction.STANDARD, GenreKeyword.all(), subject.toPromptText()));

		System.out.println("[manual] 장르=" + result.genreKeyword() + " 실서빙모델=" + result.model());
		assertThat(GenreKeyword.contains(result.genreKeyword())).isTrue();
		assertThat(result.provider()).isEqualTo(GenreProvider.OPENAI);
		assertThat(result.model()).isNotBlank();
	}
}
