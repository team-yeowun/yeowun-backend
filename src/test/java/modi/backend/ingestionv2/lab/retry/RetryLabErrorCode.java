package modi.backend.ingestionv2.lab.retry;

import org.springframework.http.HttpStatus;

import modi.backend.support.error.ErrorCode;

/**
 * lab 변형만 쓰는 오류 어휘 - 채택되지 않은 변형의 코드를 프로덕션에 남기지 않기 위한 자리.
 *
 * <ul>
 *   <li>SKIP LOCKED 변형(V3)의 "빈 결과 = 다른 요청이 처리 중" 응답이 여기 하나뿐</li>
 *   <li>채택되면 이 항목을 {@code IngestionErrorCode} 로 옮기고 이 파일은 사라진다</li>
 *   <li>낙관 충돌(V1·P1)은 프로덕션 어휘를 그대로 쓴다 - lab 이 자기 코드를 겹쳐 두지 않는다</li>
 * </ul>
 */
enum RetryLabErrorCode implements ErrorCode {

	DEAD_LETTER_REDRIVE_IN_PROGRESS(HttpStatus.CONFLICT, "DEAD_LETTER_REDRIVE_IN_PROGRESS",
			"다른 요청이 이미 이 격리 기록을 처리하고 있습니다.");

	private final HttpStatus status;
	private final String code;
	private final String message;

	RetryLabErrorCode(HttpStatus status, String code, String message) {
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
