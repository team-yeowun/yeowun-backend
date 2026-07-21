package modi.backend.application.record;

import java.util.List;

/**
 * AI 감상문 유스케이스 출력 모음.
 */
public final class RecordAiResult {

	private RecordAiResult() {
	}

	/** 생성된 질문 목록(전시 맥락 반영). */
	public record Questions(List<String> questions) {
	}

	/** 다듬어진 감상문 본문. */
	public record Compose(String content) {
	}

	/** 진행 중 draft 조회 결과. 없으면 exists=false(나머지는 빈 목록/null). */
	public record Draft(boolean exists, List<String> questions, List<RecordAiCriteria.QnaPair> answers, String content) {
	}
}
