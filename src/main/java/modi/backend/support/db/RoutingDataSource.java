package modi.backend.support.db;

import javax.sql.DataSource;

import java.util.Map;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 읽기/쓰기 분기 - 규칙 하나만 둔다.
 *
 * <ul>
 *   <li>readOnly 트랜잭션이면 복제본, 그 밖은 전부 원본</li>
 *   <li>규칙을 하나로 둔 이유 - 화면별 예외를 두기 시작하면 어떤 조회가 어디로 가는지 코드로 답할 수 없게 된다</li>
 *   <li>커넥션 획득 시점이 트랜잭션 시작보다 앞서면 판정이 항상 원본이 되므로
 *       {@code LazyConnectionDataSourceProxy} 로 감싸는 것이 이 클래스 사용의 전제</li>
 * </ul>
 */
public class RoutingDataSource extends AbstractRoutingDataSource {

	public RoutingDataSource(DataSource master, DataSource replica) {
		super.setDefaultTargetDataSource(master);
		super.setTargetDataSources(Map.of(DataSourceRole.MASTER, master, DataSourceRole.REPLICA, replica));
	}

	@Override
	protected Object determineCurrentLookupKey() {
		return TransactionSynchronizationManager.isCurrentTransactionReadOnly()
				? DataSourceRole.REPLICA
				: DataSourceRole.MASTER;
	}
}
