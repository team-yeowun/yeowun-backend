package modi.backend.domain.ai;

import java.util.function.Consumer;

/**
 * LLM 채팅 포트(provider 무관). 도메인·애플리케이션은 이 인터페이스만 의존하고,
 * 실제 provider(Claude 등)는 infra 어댑터에서 구현한다(교체 가능 — DIP).
 * 구현체는 Spring/HTTP/provider SDK를 알아도 되지만, 이 포트 자체는 순수 자바만 노출한다.
 */
public interface AiChatClient {

	/**
	 * system 지시 + user 입력으로 한 번의 완성 응답을 받아 텍스트로 반환한다.
	 * provider 미설정 시 {@link AiErrorCode#AI_DISABLED}, 생성 실패 시 {@link AiErrorCode#AI_GENERATION_FAILED}.
	 */
	String complete(String systemPrompt, String userPrompt);

	/**
	 * system + user로 완성 응답을 스트리밍으로 받아, 생성되는 텍스트 조각(델타)을 {@code onDelta}로 흘려보낸다.
	 * 토큰 단위 점진 출력으로 체감 지연을 줄이는 용도(예: 감상문 다듬기). 메서드가 반환될 때 전체 응답이 끝나 있다.
	 * provider 미설정 시 {@link AiErrorCode#AI_DISABLED}, 생성 실패 시 {@link AiErrorCode#AI_GENERATION_FAILED}.
	 * 스트리밍 미지원 provider는 전체 응답을 한 번의 델타로 넘겨 폴백해도 된다(점진 출력만 없고 동작은 유지).
	 */
	void completeStream(String systemPrompt, String userPrompt, Consumer<String> onDelta);

	/**
	 * 구조화 출력 — 응답이 {@code schemaType} 스키마(POJO/record)에 맞게 오도록 강제하고 그 타입으로 반환한다.
	 * 자연어 파싱 없이 계약된 형태의 값을 얻는다(예: 질문 개수 강제). 미지원 provider는 {@link UnsupportedOperationException} 가능.
	 * provider 미설정 시 {@link AiErrorCode#AI_DISABLED}, 생성 실패 시 {@link AiErrorCode#AI_GENERATION_FAILED}.
	 */
	<T> T completeStructured(String systemPrompt, String userPrompt, Class<T> schemaType);
}
