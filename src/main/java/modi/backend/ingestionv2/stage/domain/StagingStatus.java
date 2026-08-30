package modi.backend.ingestionv2.stage.domain;

/**
 * 스테이징 진행 상태.
 *
 * <ul>
 *   <li>PENDING: 반영 대기 또는 재시도 여지가 남은 실패</li>
 *   <li>STAGED: 코어 등록과 개장 시간 반영이 확정된 종결 상태</li>
 *   <li>FAILED: 재시도 상한 소진으로 자동 회생을 중단한 상태</li>
 * </ul>
 */
public enum StagingStatus {

	PENDING,
	STAGED,
	FAILED
}
