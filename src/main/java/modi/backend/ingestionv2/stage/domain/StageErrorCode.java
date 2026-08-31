package modi.backend.ingestionv2.stage.domain;

import org.springframework.http.HttpStatus;

import modi.backend.support.error.ErrorCode;

/**
 * 스테이징 격벽의 도메인 오류. 메시지는 여기 한 곳에만 둔다.
 *
 * <ul>
 *   <li>스테이징 안에서만 뜻을 갖는 값만 보유(공용 계층이 도메인 어휘를 알지 않게 함)</li>
 *   <li>격리 요청 신호인 RETRY_EXHAUSTED는 계층 간 약속이라 common.IngestionErrorCode 소유</li>
 *   <li>원장 값의 복원 실패는 점검과 어셈블의 판단이 어긋난 프로그래밍 오류라 500</li>
 * </ul>
 */
public enum StageErrorCode implements ErrorCode {

	STAGING_NOT_FOUND(HttpStatus.NOT_FOUND, "STAGING_NOT_FOUND", "스테이징 대상을 찾을 수 없습니다."),
	INVALID_STAGE_TRANSITION(HttpStatus.CONFLICT, "INVALID_STAGE_TRANSITION", "허용되지 않은 스테이징 상태 전이입니다."),
	LEDGER_INCOMPLETE(HttpStatus.CONFLICT, "LEDGER_INCOMPLETE", "원장이 갖추어지지 않아 조립할 수 없습니다."),
	LEDGER_VALUE_MALFORMED(HttpStatus.INTERNAL_SERVER_ERROR, "LEDGER_VALUE_MALFORMED",
			"원장 값을 코어 타입으로 복원할 수 없습니다.");

	private final HttpStatus status;
	private final String code;
	private final String message;

	StageErrorCode(HttpStatus status, String code, String message) {
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
