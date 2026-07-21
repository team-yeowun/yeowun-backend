package modi.backend.application.record;

import java.util.List;

/**
 * AI 감상문 유스케이스 입력 모음. (Facade는 Criteria/Result까지만 — Request/Response는 모름)
 */
public final class RecordAiCriteria {

	private RecordAiCriteria() {
	}

	/** 전시 맥락 기반 질문 생성 입력. */
	public record Questions(Long userId, Long exhibitionId) {
	}

	/** Q&A → 감상문 다듬기 입력. answers는 질문/답변 쌍 목록. */
	public record Compose(Long userId, Long exhibitionId, List<QnaPair> answers) {
	}

	/** 진행 중 draft 저장 입력(뒤로가기 전 자동저장). questions·answers·content는 현재 진행 상태 스냅샷. */
	public record DraftSave(Long userId, Long exhibitionId, List<String> questions, List<QnaPair> answers,
			String content) {
	}

	/** 질문/답변 한 쌍. */
	public record QnaPair(String question, String answer) {
	}
}
