package modi.backend.support.db;

/**
 * 커넥션이 향하는 DB 역할.
 *
 * <ul>
 *   <li>MASTER = 쓰기 원본, REPLICA = 읽기 복제본</li>
 *   <li>라우팅 키로만 쓰인다 - 역할 판단은 {@link RoutingDataSource} 한 곳</li>
 * </ul>
 */
public enum DataSourceRole {

	MASTER,
	REPLICA
}
