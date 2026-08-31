package modi.backend.ingestionv2.common.outbox;

/**
 * 미발행 행 조회가 향하는 DB.
 *
 * <ul>
 *   <li>운영 기본은 {@link #MASTER} - 선점은 쓰기 트랜잭션의 일부라 원본에서 읽어야 표시와 같은 곳을 본다</li>
 *   <li>{@link #REPLICA} 는 부하 실험 비교용 - 조회만 복제본으로 보내고 표시는 원본이 하는
 *       비원자 구성이라, 복제 지연 동안 이미 표시된 행을 다시 읽는 일이 실제로 일어난다</li>
 * </ul>
 */
public enum OutboxReadSource {

	MASTER,
	REPLICA
}
