package modi.backend.ingestionv2.lab.outbox;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;

import modi.backend.ingestionv2.common.event.IngestionEventType;
import modi.backend.ingestionv2.common.event.OutboxPayload;
import modi.backend.ingestionv2.common.outbox.OutboxStatus;

/**
 * 규모 사다리 시더 - JDBC 배치 적재.
 *
 * <ul>
 *   <li>누적 적재: S0 -> S1 -> S2 -> S3 로 행을 덧붙인다(점마다 truncate 하지 않아 곡선이 끊기지 않는다)</li>
 *   <li>배치 5,000건, autocommit off, 배치마다 commit - 계획서가 지정한 방식</li>
 *   <li>payload 는 {@link OutboxPayload#toJson()} 산출물 그대로 - 행 크기를 임의로 부풀리지 않는다</li>
 *   <li>created_at 은 일일 유입 3,500행 간격의 등차 - 100만 행이면 285일치 유입이라는 뜻이 데이터에 박힌다</li>
 *   <li>id 는 DB auto_increment, created_at 은 시더가 계산 - 프로덕션의 출처 분리와 같은 모양</li>
 *   <li>PENDING 은 매 점마다 정확히 1,000행으로 다시 고른다(전 구간 분산, 시드 고정 난수)</li>
 * </ul>
 */
final class OutboxLabSeeder {

	static final int BATCH_SIZE = 5_000;
	private static final long RANDOM_SEED = 20260829L;
	private static final IngestionEventType[] TYPES = IngestionEventType.values();

	private static final String INSERT = """
			INSERT INTO ingestion_outbox
			  (aggregate_type, aggregate_id, event_type, payload, status, retry_count, created_at, sent_at)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?)
			""";

	private final DataSource dataSource;
	private final JdbcTemplate jdbcTemplate;
	private final LocalDateTime anchor;
	private final long maxScaleRows;
	private final String[] seedConnection;

	/**
	 * @param maxScaleRows 이번 실행이 오를 마지막 규모의 행 수. created_at 기준점을 여기서 역산하므로
	 *                     누적 도중에 행의 created_at 이 바뀌지 않는다.
	 * @param seedConnection 시딩 전용 접속 정보(url·user·password). null 이면 애플리케이션 DataSource 를 쓴다.
	 */
	OutboxLabSeeder(DataSource dataSource, JdbcTemplate jdbcTemplate, long maxScaleRows, LocalDateTime now,
			String[] seedConnection) {
		this.dataSource = dataSource;
		this.jdbcTemplate = jdbcTemplate;
		this.maxScaleRows = maxScaleRows;
		this.seedConnection = seedConnection;
		long spanDays = Math.max(1, (long) Math.ceil((double) maxScaleRows / OutboxLabScale.ROWS_PER_DAY));
		this.anchor = now.minusDays(spanDays);
	}

	/**
	 * 시딩 전용 커넥션.
	 *
	 * <p>애플리케이션 풀 대신 {@code rewriteBatchedStatements=true} 를 켠 커넥션을 따로 연다.
	 * 이 옵션이 없으면 드라이버가 addBatch 한 건마다 왕복해 500만 행 적재가 한 시간을 넘긴다.
	 * 옵션은 <b>적재 경로에만</b> 걸린다 - 측정 대상인 선점 쿼리·발송 틱은 애플리케이션 DataSource 로 돈다.
	 */
	private Connection open() throws SQLException {
		if (seedConnection == null) {
			return dataSource.getConnection();
		}
		String url = seedConnection[0];
		url += (url.contains("?") ? "&" : "?") + "rewriteBatchedStatements=true";
		return java.sql.DriverManager.getConnection(url, seedConnection[1], seedConnection[2]);
	}

	String seedConnectionDescription() {
		return seedConnection == null
				? "애플리케이션 DataSource(rewriteBatchedStatements 미적용)"
				: "시딩 전용 커넥션(rewriteBatchedStatements=true)";
	}

	record SeedResult(OutboxLabScale scale, long totalRows, long insertedRows, int pendingRows,
			double seedSeconds, double analyzeSeconds, LocalDateTime oldestCreatedAt,
			LocalDateTime newestCreatedAt) {

		Map<String, Object> toMap() {
			Map<String, Object> map = new LinkedHashMap<>();
			map.put("scale", scale.name());
			map.put("scale_meaning", scale.meaning());
			map.put("rows", totalRows);
			map.put("inserted_this_phase", insertedRows);
			map.put("pending_rows", pendingRows);
			map.put("seed_seconds", OutboxLabStats.round(seedSeconds));
			map.put("analyze_seconds", OutboxLabStats.round(analyzeSeconds));
			map.put("oldest_created_at", String.valueOf(oldestCreatedAt));
			map.put("newest_created_at", String.valueOf(newestCreatedAt));
			return map;
		}
	}

	/** 지금 테이블을 목표 규모까지 끌어올린다. 이미 넘어섰으면 비우고 다시 쌓는다. */
	SeedResult ensureScale(OutboxLabScale scale) {
		long target = scale.totalRows();
		long current = count();
		if (current > target) {
			truncate();
			current = 0;
		}
		long inserted = 0;
		long startedAt = System.nanoTime();
		if (current < target) {
			inserted = insertRange(current + 1, target);
		}
		double seedSeconds = (System.nanoTime() - startedAt) / 1_000_000_000d;

		int pending = reassignPending();

		long analyzeStartedAt = System.nanoTime();
		jdbcTemplate.execute("ANALYZE TABLE ingestion_outbox");
		double analyzeSeconds = (System.nanoTime() - analyzeStartedAt) / 1_000_000_000d;

		return new SeedResult(scale, count(), inserted, pending, seedSeconds, analyzeSeconds,
				boundary("MIN"), boundary("MAX"));
	}

	/** 임의 목표 행수의 적재 결과. 규모 사다리 enum 에 없는 중간 점(체크포인트)이 쓴다. */
	record RowsSeedResult(long totalRows, long insertedRows, int pendingRows, double seedSeconds,
			double analyzeSeconds, LocalDateTime oldestCreatedAt, LocalDateTime newestCreatedAt) {

		Map<String, Object> toMap() {
			Map<String, Object> map = new LinkedHashMap<>();
			map.put("checkpoint_rows", totalRows);
			map.put("rows", totalRows);
			map.put("inserted_this_phase", insertedRows);
			map.put("pending_rows", pendingRows);
			map.put("seed_seconds", OutboxLabStats.round(seedSeconds));
			map.put("analyze_seconds", OutboxLabStats.round(analyzeSeconds));
			map.put("oldest_created_at", String.valueOf(oldestCreatedAt));
			map.put("newest_created_at", String.valueOf(newestCreatedAt));
			return map;
		}
	}

	/**
	 * 규모 사다리 enum 에 매이지 않는 <b>임의 목표 행수</b>까지 테이블을 끌어올린다.
	 *
	 * <ul>
	 *   <li>교차점 탐색(선점 p95 가 예산을 처음 넘는 행수)처럼 촘촘한 중간 점이 필요할 때 쓴다</li>
	 *   <li>동작은 {@link #ensureScale} 과 같다 - 누적 적재 + PENDING 1,000행 재배정 + ANALYZE</li>
	 *   <li>{@code ensureScale} 을 고치지 않고 따로 둔 이유는 baseline 측정이 도는 중이라 기존 진입점의
	 *       모양을 흔들지 않기 위해서다</li>
	 * </ul>
	 */
	RowsSeedResult ensureRows(long target) {
		long current = count();
		if (current > target) {
			truncate();
			current = 0;
		}
		long inserted = 0;
		long startedAt = System.nanoTime();
		if (current < target) {
			inserted = insertRange(current + 1, target);
		}
		double seedSeconds = (System.nanoTime() - startedAt) / 1_000_000_000d;

		int pending = reassignPending();

		long analyzeStartedAt = System.nanoTime();
		jdbcTemplate.execute("ANALYZE TABLE ingestion_outbox");
		double analyzeSeconds = (System.nanoTime() - analyzeStartedAt) / 1_000_000_000d;

		return new RowsSeedResult(count(), inserted, pending, seedSeconds, analyzeSeconds,
				boundary("MIN"), boundary("MAX"));
	}

	/**
	 * step-05 3차용 - PENDING 1,000행 중 절반의 created_at 을 id 순서와 역방향으로 바꾼다.
	 *
	 * <p>실제 발생원은 두 시각의 출처가 다르다는 데 있다. created_at 은 애플리케이션 JVM 이
	 * {@code IngestionClock.now()} 로 찍고 id 는 DB 가 INSERT 시점에 붙이므로, 긴 트랜잭션이나
	 * 인스턴스 간 시계차에서 두 순서가 갈린다. 여기서는 그 결과 상태만 만든다.
	 *
	 * @return 뒤집기 전 (id, created_at) 목록 - 되돌릴 때 쓴다
	 */
	List<Object[]> skewHalfOfPending() {
		List<Map<String, Object>> pending = jdbcTemplate.queryForList(
				"SELECT id, created_at FROM ingestion_outbox WHERE status = 'PENDING' ORDER BY id");
		List<Object[]> original = pending.stream()
				.map(row -> new Object[] {row.get("id"), row.get("created_at")})
				.collect(Collectors.toCollection(ArrayList::new));

		List<Object[]> selected = new ArrayList<>();
		for (int index = 0; index < original.size(); index += 2) {
			selected.add(original.get(index));
		}
		List<Object[]> updates = new ArrayList<>();
		for (int index = 0; index < selected.size(); index++) {
			Object id = selected.get(index)[0];
			Object reversedCreatedAt = selected.get(selected.size() - 1 - index)[1];
			updates.add(new Object[] {reversedCreatedAt, id});
		}
		jdbcTemplate.batchUpdate("UPDATE ingestion_outbox SET created_at = ? WHERE id = ?", updates);
		return original;
	}

	/** skewHalfOfPending 이 돌려준 원본으로 created_at 을 되돌린다. */
	void restoreCreatedAt(List<Object[]> original) {
		List<Object[]> updates = original.stream()
				.map(row -> new Object[] {row[1], row[0]})
				.toList();
		jdbcTemplate.batchUpdate("UPDATE ingestion_outbox SET created_at = ? WHERE id = ?", updates);
	}

	/** step-04 용 - 한 틱이 건드린 행을 미발행으로 되돌린다. */
	void resetPendingRows(List<Long> ids) {
		if (ids.isEmpty()) {
			return;
		}
		String placeholders = ids.stream().map(id -> "?").collect(Collectors.joining(","));
		jdbcTemplate.update("UPDATE ingestion_outbox SET status = 'PENDING', sent_at = NULL, retry_count = 0"
				+ " WHERE id IN (" + placeholders + ")", ids.toArray());
	}

	/** step-01 하위 조건 (B) 용 - 미발행 행을 잠시 비운다(드레인 직후 상태). PK 로 지목하므로 인덱스 유무와 무관. */
	void markSent(List<Long> ids) {
		if (ids.isEmpty()) {
			return;
		}
		for (int offset = 0; offset < ids.size(); offset += 500) {
			List<Long> chunk = ids.subList(offset, Math.min(offset + 500, ids.size()));
			String placeholders = chunk.stream().map(id -> "?").collect(Collectors.joining(","));
			jdbcTemplate.update("UPDATE ingestion_outbox SET status = 'SENT', sent_at = created_at, retry_count = 1"
					+ " WHERE id IN (" + placeholders + ")", chunk.toArray());
		}
	}

	List<Long> pendingIds() {
		return jdbcTemplate.queryForList(
				"SELECT id FROM ingestion_outbox WHERE status = 'PENDING' ORDER BY created_at, id", Long.class);
	}

	long count() {
		Long rows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ingestion_outbox", Long.class);
		return rows == null ? 0L : rows;
	}

	Map<String, Long> countByStatus() {
		Map<String, Long> counts = new LinkedHashMap<>();
		jdbcTemplate.queryForList("SELECT status, COUNT(*) AS c FROM ingestion_outbox GROUP BY status")
				.forEach(row -> counts.put(String.valueOf(row.get("status")), ((Number) row.get("c")).longValue()));
		return counts;
	}

	void truncate() {
		jdbcTemplate.execute("TRUNCATE TABLE ingestion_outbox");
	}

	LocalDateTime anchor() {
		return anchor;
	}

	long maxScaleRows() {
		return maxScaleRows;
	}

	private LocalDateTime boundary(String function) {
		Timestamp value = jdbcTemplate.queryForObject(
				"SELECT " + function + "(created_at) FROM ingestion_outbox", Timestamp.class);
		return value == null ? null : value.toLocalDateTime();
	}

	private long insertRange(long fromSequence, long toSequence) {
		long inserted = 0;
		try (Connection connection = open()) {
			boolean autoCommit = connection.getAutoCommit();
			connection.setAutoCommit(false);
			try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
				int inBatch = 0;
				for (long sequence = fromSequence; sequence <= toSequence; sequence++) {
					bind(statement, sequence);
					statement.addBatch();
					inBatch++;
					if (inBatch == BATCH_SIZE) {
						statement.executeBatch();
						connection.commit();
						inserted += inBatch;
						inBatch = 0;
					}
				}
				if (inBatch > 0) {
					statement.executeBatch();
					connection.commit();
					inserted += inBatch;
				}
			} catch (SQLException failure) {
				connection.rollback();
				throw failure;
			} finally {
				connection.setAutoCommit(autoCommit);
			}
		} catch (SQLException failure) {
			throw new IllegalStateException("아웃박스 시딩에 실패했습니다.", failure);
		}
		return inserted;
	}

	private void bind(PreparedStatement statement, long sequence) throws SQLException {
		IngestionEventType type = TYPES[(int) (sequence % TYPES.length)];
		String aggregateId = "EXH-%08d".formatted(sequence);
		LocalDateTime createdAt = createdAt(sequence);
		String payload = OutboxPayload.of(type, aggregateId, createdAt).toJson();
		statement.setString(1, type.aggregateType().name());
		statement.setString(2, aggregateId);
		statement.setString(3, type.name());
		statement.setString(4, payload);
		statement.setString(5, OutboxStatus.SENT.name());
		statement.setInt(6, 1);
		statement.setTimestamp(7, Timestamp.valueOf(createdAt));
		statement.setTimestamp(8, Timestamp.valueOf(createdAt.plusSeconds(2)));
	}

	/** 일일 유입 3,500행 간격의 등차 배치. 1번 행이 가장 오래됐고 마지막 행이 가장 최근이다. */
	private LocalDateTime createdAt(long sequence) {
		long micros = Math.round((sequence - 1) * (86_400_000_000d / OutboxLabScale.ROWS_PER_DAY));
		return anchor.plusNanos(micros * 1_000L);
	}

	/**
	 * PENDING 을 정확히 1,000행으로 다시 고른다.
	 *
	 * <p>id 는 갓 비운 테이블에 단일 스레드로 넣어 연속이므로 [min, max] 구간에서 시드 고정 난수로
	 * 고른다. 연속이 아니면(재실행 잔여 등) 부족분만 무작위 SENT 행에서 채운다.
	 */
	private int reassignPending() {
		jdbcTemplate.update("UPDATE ingestion_outbox SET status = 'SENT', sent_at = created_at, retry_count = 1"
				+ " WHERE status <> 'SENT'");
		Map<String, Object> bounds = jdbcTemplate.queryForMap(
				"SELECT MIN(id) AS lo, MAX(id) AS hi, COUNT(*) AS c FROM ingestion_outbox");
		long low = ((Number) bounds.get("lo")).longValue();
		long high = ((Number) bounds.get("hi")).longValue();
		long rows = ((Number) bounds.get("c")).longValue();

		List<Long> chosen = new ArrayList<>();
		Random random = new Random(RANDOM_SEED);
		long stride = Math.max(1, rows / OutboxLabScale.PENDING_ROWS);
		for (int index = 0; index < OutboxLabScale.PENDING_ROWS; index++) {
			long slotStart = low + index * stride;
			long candidate = slotStart + (stride > 1 ? random.nextInt((int) Math.min(stride, Integer.MAX_VALUE)) : 0);
			chosen.add(Math.min(candidate, high));
		}
		List<Long> distinct = chosen.stream().distinct().toList();
		int updated = markPending(distinct);
		if (updated < OutboxLabScale.PENDING_ROWS) {
			List<Long> filler = jdbcTemplate.queryForList(
					"SELECT id FROM ingestion_outbox WHERE status = 'SENT' ORDER BY RAND() LIMIT "
							+ (OutboxLabScale.PENDING_ROWS - updated), Long.class);
			updated += markPending(filler);
		}
		return updated;
	}

	private int markPending(List<Long> ids) {
		int updated = 0;
		for (int offset = 0; offset < ids.size(); offset += 500) {
			List<Long> chunk = ids.subList(offset, Math.min(offset + 500, ids.size()));
			String placeholders = chunk.stream().map(id -> "?").collect(Collectors.joining(","));
			updated += jdbcTemplate.update(
					"UPDATE ingestion_outbox SET status = 'PENDING', sent_at = NULL, retry_count = 0"
							+ " WHERE status = 'SENT' AND id IN (" + placeholders + ")", chunk.toArray());
		}
		return updated;
	}
}
