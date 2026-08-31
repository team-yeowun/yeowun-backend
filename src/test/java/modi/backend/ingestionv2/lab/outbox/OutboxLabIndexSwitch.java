package modi.backend.ingestionv2.lab.outbox;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * before 복원 스위치 - 프로토콜 §2 의 A(스키마 조작).
 *
 * <ul>
 *   <li>V55 적용 후 이 테이블에 남는 인덱스는 PRIMARY(id) 와 idx_ingestion_outbox_status_created 둘뿐이라
 *       후자를 지우면 PK 만 남은 진짜 무인덱스 상태가 된다("약화된 after"가 아니다)</li>
 *   <li>매 전환마다 SHOW INDEX 출력을 캡처해 원시 파일에 남긴다 - 진위 확인의 근거</li>
 *   <li>DDL 소요 시간도 잰다 - 인덱스 생성 비용이 after 의 대가라서</li>
 *   <li>@AfterAll 에서 반드시 현행 인덱스를 되돌린다(컨테이너 공유 오염 방지)</li>
 * </ul>
 */
final class OutboxLabIndexSwitch {

	static final String CURRENT_INDEX = "idx_ingestion_outbox_status_created";
	static final String CURRENT_COLUMNS = "(status, created_at)";

	/** step-05 의 정렬 키 후보. ①없음 은 인덱스를 하나도 만들지 않은 상태다. */
	enum Candidate {
		NONE("①없음", null, null),
		STATUS("②(status)", "lab_idx_outbox_status", "(status)"),
		STATUS_ID("③(status, id)", "lab_idx_outbox_status_id", "(status, id)"),
		STATUS_CREATED("④(status, created_at)", CURRENT_INDEX, CURRENT_COLUMNS);

		private final String label;
		private final String indexName;
		private final String columns;

		Candidate(String label, String indexName, String columns) {
			this.label = label;
			this.indexName = indexName;
			this.columns = columns;
		}

		String label() {
			return label;
		}

		String indexName() {
			return indexName;
		}

		String columns() {
			return columns;
		}
	}

	private final JdbcTemplate jdbcTemplate;

	OutboxLabIndexSwitch(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/** SHOW INDEX 원문 - 컬럼을 그대로 늘어놓는다. */
	String showIndex() {
		List<Map<String, Object>> rows = jdbcTemplate.queryForList("SHOW INDEX FROM ingestion_outbox");
		StringBuilder text = new StringBuilder("SHOW INDEX FROM ingestion_outbox;\n\n");
		if (rows.isEmpty()) {
			return text.append("(행 없음)\n").toString();
		}
		List<String> columns = List.copyOf(rows.get(0).keySet());
		text.append(String.join(" | ", columns)).append('\n');
		for (Map<String, Object> row : rows) {
			text.append(columns.stream().map(column -> String.valueOf(row.get(column)))
					.reduce((left, right) -> left + " | " + right).orElse("")).append('\n');
		}
		return text.toString();
	}

	boolean exists(String indexName) {
		Integer count = jdbcTemplate.queryForObject("""
				SELECT COUNT(*) FROM information_schema.STATISTICS
				WHERE table_schema = DATABASE() AND table_name = 'ingestion_outbox' AND index_name = ?
				""", Integer.class, indexName);
		return count != null && count > 0;
	}

	/** 인덱스를 지운다. 이미 없으면 아무것도 하지 않고 0 을 돌려준다. */
	double dropIndex(String indexName) {
		if (!exists(indexName)) {
			return 0d;
		}
		long startedAt = System.nanoTime();
		jdbcTemplate.execute("DROP INDEX " + indexName + " ON ingestion_outbox");
		return (System.nanoTime() - startedAt) / 1_000_000d;
	}

	/** 인덱스를 만든다. 이미 있으면 아무것도 하지 않고 0 을 돌려준다. 돌려주는 값은 DDL 소요 시간(ms). */
	double createIndex(String indexName, String columns) {
		if (exists(indexName)) {
			return 0d;
		}
		long startedAt = System.nanoTime();
		jdbcTemplate.execute("CREATE INDEX " + indexName + " ON ingestion_outbox " + columns);
		return (System.nanoTime() - startedAt) / 1_000_000d;
	}

	double dropCurrent() {
		return dropIndex(CURRENT_INDEX);
	}

	double createCurrent() {
		return createIndex(CURRENT_INDEX, CURRENT_COLUMNS);
	}

	/** 후보 하나만 남긴 상태로 만든다(나머지 lab 인덱스와 현행 인덱스는 전부 제거). */
	double applyOnly(Candidate candidate) {
		double ddlMillis = 0d;
		for (Candidate other : Candidate.values()) {
			if (other == candidate || other.indexName() == null) {
				continue;
			}
			dropIndex(other.indexName());
		}
		if (candidate.indexName() != null) {
			ddlMillis = createIndex(candidate.indexName(), candidate.columns());
		} else {
			dropCurrent();
		}
		return ddlMillis;
	}

	/** lab 이 만든 인덱스를 모두 지우고 현행 인덱스만 남긴다. */
	void restoreProduction() {
		for (Candidate candidate : Candidate.values()) {
			if (candidate.indexName() != null && !CURRENT_INDEX.equals(candidate.indexName())) {
				dropIndex(candidate.indexName());
			}
		}
		createCurrent();
	}
}
