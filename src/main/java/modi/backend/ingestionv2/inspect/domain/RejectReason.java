package modi.backend.ingestionv2.inspect.domain;

/**
 * 반려 사유 (진행을 멈추는 결함).
 *
 * <ul>
 *   <li>항목별로 나눔 (관리자가 원장을 열어보기 전에 무엇이 깨졌는지 알 수 있어야 함)</li>
 *   <li>공통 어휘 하나로 뭉치지 않음 (사유가 하나뿐이면 반려 목록에서 원인별 집계가 불가능)</li>
 * </ul>
 */
public enum RejectReason {

	/** 제목이 비어 있음. */
	TITLE_BLANK,

	/** 시작일이 벤더 포맷으로 파싱되지 않음. */
	START_DATE_UNPARSABLE,

	/** 종료일이 벤더 포맷으로 파싱되지 않음. */
	END_DATE_UNPARSABLE,

	/** 종료일이 시작일보다 앞섬. */
	PERIOD_REVERSED,

	/** 장르 키워드가 비어 있음. */
	GENRE_BLANK
}
