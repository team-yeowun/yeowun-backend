package modi.backend.domain.ai;

import java.util.List;

/**
 * AI '질문으로 작성' 진행 중 임시저장 값(비영속 캐시 전용). 질문 3개 + 사용자의 Q&A 답변 + 다듬은 초안(content).
 * 뒤로가기/재진입 시 이 값을 그대로 복원해 같은 질문·답변으로 이어 쓰게 한다.
 * 순수 자바 값(도메인)이며 provider·Redis를 모른다 — 저장 위치는 {@link AiDraftStore} 포트가 추상화한다.
 */
public record AiDraft(List<String> questions, List<Qna> answers, String content) {

	public AiDraft {
		questions = questions == null ? List.of() : List.copyOf(questions);
		answers = answers == null ? List.of() : List.copyOf(answers);
	}

	/** 질문/답변 한 쌍. */
	public record Qna(String question, String answer) {
	}

	/** 질문만 생성된 시점의 draft(답변·초안 아직 없음). */
	public static AiDraft ofQuestions(List<String> questions) {
		return new AiDraft(questions, List.of(), null);
	}
}
