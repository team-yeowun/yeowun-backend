package modi.backend.infra.ai.claude;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;

import modi.backend.application.record.AiQuestionsOutput;

/**
 * 질문 생성(구조화 출력)의 <b>스키마 생성 경로</b> 회귀 테스트 — 네트워크·api-key 없이 클래스패스만 검증한다.
 * {@code outputConfig(Class)}는 anthropic SDK 안에서 victools(jsonschema-generator)로 JSON Schema를 만드는데,
 * 이 라이브러리 메이저가 어긋나면 {@code NoSuchMethodError}가 난다(Error라서 어댑터의 catch(Exception)도,
 * GlobalExceptionHandler도 그대로 통과해 원인 없는 500으로만 보임 — 실제로 그렇게 터진 적이 있다).
 * AiChatClient를 모킹하는 파사드 테스트로는 절대 잡히지 않으므로 여기서 실제 SDK 호출로 고정한다.
 * (build.gradle의 victools 4.38.0 pin이 사라지면 이 테스트가 먼저 깨진다.)
 */
class ClaudeStructuredOutputTest {

	@Test
	@DisplayName("질문 3개 스키마를 SDK가 생성한다 — 계열 라이브러리(victools) 충돌 감시")
	void outputConfig_질문스키마_생성성공() {
		assertThatCode(() -> buildParams()).doesNotThrowAnyException();

		StructuredMessageCreateParams<AiQuestionsOutput> params = buildParams();
		// 스키마에 질문 3개가 필수로 박혀야 "정확히 3개"가 API 레벨에서 강제된다.
		assertThat(params.toString())
				.contains("question1")
				.contains("question2")
				.contains("question3");
	}

	private StructuredMessageCreateParams<AiQuestionsOutput> buildParams() {
		return MessageCreateParams.builder()
				.model("claude-sonnet-5")
				.maxTokens(768L)
				.system("system")
				.addUserMessage("user")
				.outputConfig(AiQuestionsOutput.class)
				.build();
	}
}
