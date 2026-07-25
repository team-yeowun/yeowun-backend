package modi.backend.ingestion.application.outbox;

import modi.backend.ingestion.domain.outbox.OutboxFailureType;

/**
 * 이벤트 스텝 실행 한 건의 판정 — 파사드의 스텝 메서드가 돌려주고, {@link ExhibitionOutboxService#consume}이
 * 이 값대로 메시지를 전이시킨다(성공/실패는 전이, {@link #skip()}은 무전이 — 낙관락 선점 등 "남이 처리").
 *
 * <p>이 record가 있어야 소비 수명주기(선별→스텝→전이)가 아웃박스 메커니즘 안으로 들어가고, 파사드는
 * [서비스 호출 합성 + 예외→StepResult 매핑]만 남는다 — 흐름제어의 최소 단위.
 */
public record StepResult(Outcome outcome, OutboxFailureType failureType, String error) {

	public enum Outcome {
		/** 스텝 해소(할 일 없음 포함 — 멱등 소비의 성공 마감) → markSucceeded. */
		SUCCESS,
		/** 무전이 skip — 낙관락 충돌 등 다른 워커가 선점했다(집계에서도 빠진다). */
		SKIP,
		/** 실패 전이 → markFailed(백오프·소진 승격은 도메인 정책). */
		FAIL
	}

	public static StepResult success() {
		return new StepResult(Outcome.SUCCESS, null, null);
	}

	public static StepResult skip() {
		return new StepResult(Outcome.SKIP, null, null);
	}

	public static StepResult fail(OutboxFailureType failureType, String error) {
		return new StepResult(Outcome.FAIL, failureType, error);
	}
}
