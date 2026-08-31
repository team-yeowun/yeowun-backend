package modi.backend.ingestionv2.common.outbox;

import java.util.List;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 미발행 행 조회를 복제본으로 보내는 경로 - 부하 실험 비교용.
 *
 * <ul>
 *   <li>복제본 주소가 설정된 환경에서만 등록된다 - 없으면 빈 자체가 없고 {@code outbox-read=REPLICA} 는 무시된다</li>
 *   <li>돌려주는 것은 식별자뿐 - 표시(UPDATE)는 원본 트랜잭션이 그대로 소유해야 하므로 엔티티는 원본에서 읽는다</li>
 *   <li>비원자 구성 - 조회 커넥션이 원본 트랜잭션 밖에 있어 (a) 복제본에서 잡은 잠금은 커밋 없이 곧바로 풀리고
 *       다른 인스턴스에게 보이지도 않으며, (b) 복제 지연 동안 원본에서 이미 표시된 행이 여전히 미발행으로 읽힌다.
 *       이 둘이 읽기 복제 분산을 철회한 이유이고, 이 클래스는 그 사실을 재현하기 위해서만 존재한다</li>
 *   <li>읽은 행의 상태를 다시 확인하지 않는다 - 다시 확인하면 재현하려는 그 지연이 가려진다</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "app.datasource.replica", name = "url")
public class OutboxReplicaReader {

	private static final String BASE = """
			SELECT id FROM ingestion_outbox
			WHERE status = 'PENDING'
			ORDER BY created_at, id
			""";

	private final JdbcTemplate replicaJdbcTemplate;

	public OutboxReplicaReader(@Qualifier("replicaDataSource") DataSource replicaDataSource) {
		this.replicaJdbcTemplate = new JdbcTemplate(replicaDataSource);
	}

	/** 복제본에서 미발행 행의 식별자를 읽는다. limit 이 0 이면 상한 절을 붙이지 않는다. */
	public List<Long> readPendingIds(int limit, OutboxClaimStrategy strategy) {
		String sql = BASE + (limit > 0 ? "LIMIT " + limit + "\n" : "") + lockClause(strategy);
		return replicaJdbcTemplate.queryForList(sql, Long.class);
	}

	private static String lockClause(OutboxClaimStrategy strategy) {
		return switch (strategy) {
			case PESSIMISTIC -> "FOR UPDATE";
			case SKIP_LOCKED -> "FOR UPDATE SKIP LOCKED";
			case NONE, REDIS_MARKER -> "";
		};
	}
}
