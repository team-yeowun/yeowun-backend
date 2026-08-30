package modi.backend.ingestionv2.inspect.domain;

/**
 * 점검 결과 상태.
 *
 * <ul>
 *   <li>진행 중 상태 없음 (점검은 외부 호출이 없어 시작과 끝 사이에 관측할 구간이 없음)</li>
 *   <li>실패 상태 없음 (반려는 벤더 데이터의 문제이고, 점검 자체의 실패는 행을 남기지 않음)</li>
 * </ul>
 */
public enum InspectionStatus {

	/** 코어에 등록해도 되는 데이터로 확인됨. */
	PASSED,

	/** 필수 값이 쓸 수 없는 상태라 진행을 멈춤. */
	REJECTED
}
