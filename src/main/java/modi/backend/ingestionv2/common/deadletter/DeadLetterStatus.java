package modi.backend.ingestionv2.common.deadletter;

/**
 * 격리 항목의 관리 상태.
 *
 * <ul>
 *   <li>PENDING: 관리자가 아직 보지 않음</li>
 *   <li>REPLAYED: 아웃박스에 새 행으로 되돌려 보냄</li>
 *   <li>IGNORED: 처리하지 않기로 함 - 기록만 남긴다</li>
 * </ul>
 */
public enum DeadLetterStatus {
	PENDING,
	REPLAYED,
	IGNORED
}
