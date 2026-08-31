package modi.backend.ingestionv2.inspect.domain;

import org.springframework.http.HttpStatus;

import modi.backend.support.error.ErrorCode;

/**
 * 점검 격벽의 도메인 오류. 메시지는 이 열거형 한 곳에만 둔다.
 *
 * <ul>
 *   <li>결손은 벤더 데이터의 문제가 아니라 선행 격벽의 불변식 위반</li>
 *   <li>재점검 입력 오류도 여기서 어휘를 가짐</li>
 *   <li>배달 계층의 어휘(격리 신호)는 여기 두지 않음</li>
 * </ul>
 */
public enum InspectErrorCode implements ErrorCode {

	LEDGER_MISSING(HttpStatus.CONFLICT, "LEDGER_MISSING", "원장 단면이 없어 점검할 수 없습니다."),
	INSPECTION_NOT_FOUND(HttpStatus.NOT_FOUND, "INSPECTION_NOT_FOUND", "점검 기록을 찾을 수 없습니다."),
	INSPECTION_NOT_REJECTED(HttpStatus.CONFLICT, "INSPECTION_NOT_REJECTED", "반려된 전시만 다시 점검할 수 있습니다.");

	private final HttpStatus status;
	private final String code;
	private final String message;

	InspectErrorCode(HttpStatus status, String code, String message) {
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
