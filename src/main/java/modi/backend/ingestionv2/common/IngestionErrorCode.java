package modi.backend.ingestionv2.common;

import org.springframework.http.HttpStatus;

import modi.backend.support.error.ErrorCode;

/**
 * 배달 계층의 오류 어휘. 이 enum이 소유하는 것은 두 가지뿐이다.
 *
 * <ul>
 *   <li>배달 계층 자신의 오류 - 발행 기록 미발견, 레코드 해석 불가, 대기열 발행 실패, 격리 조회</li>
 *   <li>RETRY_EXHAUSTED - 도메인 사정이 아니라 도메인이 배달 계층에 격리를 요청하는 계층 간 약속</li>
 *   <li>도메인 사정의 오류는 각 도메인이 자기 패키지의 열거형에 둔다(공용이 도메인 어휘를 모르게)</li>
 * </ul>
 */
public enum IngestionErrorCode implements ErrorCode {

	RETRY_EXHAUSTED(HttpStatus.CONFLICT, "RETRY_EXHAUSTED", "재시도 상한을 넘어 처리를 중단했습니다."),
	OUTBOX_NOT_FOUND(HttpStatus.NOT_FOUND, "OUTBOX_NOT_FOUND", "발행 기록을 찾을 수 없습니다."),
	OUTBOX_NOT_RETRYABLE(HttpStatus.CONFLICT, "OUTBOX_NOT_RETRYABLE", "발행 실패 상태가 아닌 기록은 다시 시도할 수 없습니다."),
	EVENT_RECORD_MALFORMED(HttpStatus.INTERNAL_SERVER_ERROR, "EVENT_RECORD_MALFORMED", "배달 레코드를 해석할 수 없습니다."),
	STREAM_PUBLISH_FAILED(HttpStatus.BAD_GATEWAY, "STREAM_PUBLISH_FAILED", "대기열 발행에 실패했습니다."),
	STREAM_NOT_ROUTED(HttpStatus.INTERNAL_SERVER_ERROR, "STREAM_NOT_ROUTED", "배정된 대기열이 없는 이벤트입니다."),
	OUTBOX_READ_SOURCE_UNAVAILABLE(HttpStatus.INTERNAL_SERVER_ERROR, "OUTBOX_READ_SOURCE_UNAVAILABLE",
			"선택한 조회 대상 데이터소스가 없습니다."),
	DEAD_LETTER_NOT_FOUND(HttpStatus.NOT_FOUND, "DEAD_LETTER_NOT_FOUND", "격리 기록을 찾을 수 없습니다."),
	DEAD_LETTER_ALREADY_RESOLVED(HttpStatus.CONFLICT, "DEAD_LETTER_ALREADY_RESOLVED", "이미 처리한 격리 기록입니다."),
	DEAD_LETTER_NOT_REDRIVABLE(HttpStatus.CONFLICT, "DEAD_LETTER_NOT_REDRIVABLE", "되돌려 보낼 수 없는 격리 기록입니다."),
	DEAD_LETTER_REDRIVE_CONFLICT(HttpStatus.CONFLICT, "DEAD_LETTER_REDRIVE_CONFLICT",
			"다른 요청이 먼저 이 격리 기록을 되돌려 보냈습니다.");

	private final HttpStatus status;
	private final String code;
	private final String message;

	IngestionErrorCode(HttpStatus status, String code, String message) {
		this.status = status;
		this.code = code;
		this.message = message;
	}

	@Override
	public String code() {
		return code;
	}

	@Override
	public String message() {
		return message;
	}

	@Override
	public HttpStatus getStatus() {
		return status;
	}
}
