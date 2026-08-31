package modi.backend.ingestionv2.collect.domain;

import org.springframework.http.HttpStatus;

import modi.backend.support.error.ErrorCode;

/**
 * 수집 격벽의 도메인 오류. 메시지는 여기 한 곳에만 둔다.
 *
 * <ul>
 *   <li>원천 키 누락은 상관 키가 없어 행을 만들 자리가 없는 경우</li>
 *   <li>목록 호출 실패는 전송·역직렬화·벤더 실패를 한 어휘로 번역한 값</li>
 *   <li>공용 계층이 아니라 수집 패키지 소유 (common이 수집 어휘를 알지 않게)</li>
 * </ul>
 */
public enum CollectErrorCode implements ErrorCode {

	INVALID_VENDOR_KEY(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_VENDOR_KEY", "원천 키가 없는 전시입니다."),
	EXTERNAL_CALL_FAILED(HttpStatus.BAD_GATEWAY, "EXTERNAL_CALL_FAILED", "문화포털 목록 조회에 실패했습니다.");

	private final HttpStatus status;
	private final String code;
	private final String message;

	CollectErrorCode(HttpStatus status, String code, String message) {
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
