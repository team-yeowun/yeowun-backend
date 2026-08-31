package modi.backend.ingestionv2.enrich.domain;

import org.springframework.http.HttpStatus;

import modi.backend.support.error.ErrorCode;

/**
 * 보강 격벽의 도메인 오류. 메시지는 여기 한 곳에만 둔다.
 *
 * <ul>
 *   <li>외부 호출 실패는 재전달에서 정상적으로 발생하는 사건. 핸들러가 시도 횟수로 번역</li>
 *   <li>관리자 재시도 API의 입력 오류도 여기서 어휘를 가짐</li>
 * </ul>
 */
public enum EnrichErrorCode implements ErrorCode {

	ENRICHMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "ENRICHMENT_NOT_FOUND", "보강 대상을 찾을 수 없습니다."),
	ENRICHMENT_NOT_FAILED(HttpStatus.CONFLICT, "ENRICHMENT_NOT_FAILED", "실패한 보강만 다시 실행할 수 있습니다."),
	DETAIL_LEDGER_MISSING(HttpStatus.CONFLICT, "DETAIL_LEDGER_MISSING", "상세 원장이 없어 보강을 이어갈 수 없습니다."),
	DETAIL_FETCH_FAILED(HttpStatus.BAD_GATEWAY, "DETAIL_FETCH_FAILED", "문화포털 상세 조회에 실패했습니다."),
	GENRE_CLASSIFY_FAILED(HttpStatus.BAD_GATEWAY, "GENRE_CLASSIFY_FAILED", "장르 분류에 실패했습니다."),
	PLACE_FETCH_FAILED(HttpStatus.BAD_GATEWAY, "PLACE_FETCH_FAILED", "개장 시간 조회에 실패했습니다.");

	private final HttpStatus status;
	private final String code;
	private final String message;

	EnrichErrorCode(HttpStatus status, String code, String message) {
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
