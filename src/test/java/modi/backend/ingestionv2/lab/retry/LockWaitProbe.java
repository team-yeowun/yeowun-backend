package modi.backend.ingestionv2.lab.retry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 잠금 대기의 흔적을 DB 쪽에서 직접 뜨는 관측기 - 계획서 step-07 지표 5.
 *
 * <ul>
 *   <li>집계는 {@code SHOW GLOBAL STATUS LIKE 'Innodb_row_lock%'} 의 run 전후 델타</li>
 *   <li>{@code Innodb_row_lock_time_avg}·{@code max} 는 누적 통계라 델타가 아니라 전후 값을 그대로 싣는다</li>
 *   <li>원문 표본은 <b>쓸 수 있는 출처를 먼저 확인하고</b> 고른다 - 컨테이너 계정이 performance_schema 나
 *       PROCESS 권한을 갖지 못하는 경우가 있어, 한 출처만 믿으면 첨부가 통째로 비어 버린다</li>
 *   <li>출처 우선순위: {@code performance_schema.data_lock_waits} → {@code information_schema.innodb_trx}
 *       → {@code SHOW ENGINE INNODB STATUS} 발췌</li>
 *   <li>쓸 수 있는 출처가 하나도 없으면 그 사실과 각 출처의 실패 이유를 첨부에 남긴다 - 빈 파일은 증거가 아니다</li>
 *   <li>표본 수에 상한을 둔다 - 50스레드 run 에서 첨부가 수십 MB 로 부풀지 않게</li>
 * </ul>
 */
final class LockWaitProbe {

	private static final int MAX_SAMPLES = 200;
	private static final int INNODB_STATUS_EXCERPT = 4_000;

	private static final List<String> DELTA_KEYS = List.of(
			"Innodb_row_lock_waits", "Innodb_row_lock_time", "Innodb_row_lock_current_waits");
	private static final List<String> LEVEL_KEYS = List.of(
			"Innodb_row_lock_time_avg", "Innodb_row_lock_time_max");

	private final JdbcTemplate jdbcTemplate;
	private final long intervalMs;
	private final AtomicBoolean running = new AtomicBoolean();
	private final List<String> samples = new ArrayList<>();
	private final Map<String, String> sourceAvailability = new LinkedHashMap<>();
	private Map<String, Long> before = Map.of();
	private Map<String, Long> after = Map.of();
	private Thread sampler;

	LockWaitProbe(JdbcTemplate jdbcTemplate, long intervalMs) {
		this.jdbcTemplate = jdbcTemplate;
		this.intervalMs = intervalMs;
	}

	void start() {
		before = readStatus();
		samples.clear();
		probeSources();
		if (intervalMs <= 0) {
			return;
		}
		running.set(true);
		sampler = new Thread(this::sampleLoop, "retry-lab-lock-probe");
		sampler.setDaemon(true);
		sampler.start();
	}

	void stop() {
		running.set(false);
		if (sampler != null) {
			try {
				sampler.join(2_000L);
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
			}
			sampler = null;
		}
		after = readStatus();
	}

	/** run 전후 델타와 수준값. 원시 파일 조건에 그대로 들어간다. */
	Map<String, Object> summary() {
		Map<String, Object> summary = new LinkedHashMap<>();
		for (String key : DELTA_KEYS) {
			summary.put(key + "_delta", after.getOrDefault(key, 0L) - before.getOrDefault(key, 0L));
		}
		for (String key : LEVEL_KEYS) {
			summary.put(key + "_before", before.getOrDefault(key, 0L));
			summary.put(key + "_after", after.getOrDefault(key, 0L));
		}
		summary.put("lock_wait_samples", samples.size());
		summary.put("lock_wait_sources", new LinkedHashMap<>(sourceAvailability));
		return summary;
	}

	/** 첨부로 나갈 원문. 표본이 없으면 출처별 가용성이 그 자리를 대신한다. */
	String rawSamples() {
		StringBuilder text = new StringBuilder();
		text.append("[출처 가용성]\n");
		sourceAvailability.forEach((source, state) -> text.append(source).append(" -> ").append(state).append("\n"));
		text.append("\n[표본 ").append(samples.size()).append("건 · 채취 주기 ").append(intervalMs).append("ms]\n");
		if (samples.isEmpty()) {
			text.append("표본 없음 - 채취 구간에서 대기 중인 트랜잭션이 잡히지 않았거나 출처를 쓸 수 없었습니다.\n");
		} else {
			samples.forEach(sample -> text.append(sample).append("\n"));
		}
		return text.toString();
	}

	/** 어느 출처를 쓸 수 있는지 먼저 확인한다. 실패 이유도 그대로 남긴다. */
	private void probeSources() {
		sourceAvailability.clear();
		sourceAvailability.put("performance_schema.data_lock_waits",
				tryQuery("select * from performance_schema.data_lock_waits limit 1"));
		sourceAvailability.put("information_schema.innodb_trx",
				tryQuery("select trx_id from information_schema.innodb_trx limit 1"));
		sourceAvailability.put("show engine innodb status", tryQuery("show engine innodb status"));
	}

	private String tryQuery(String sql) {
		try {
			jdbcTemplate.queryForList(sql);
			return "OK";
		} catch (RuntimeException unavailable) {
			return "UNAVAILABLE: " + rootMessage(unavailable);
		}
	}

	private boolean usable(String source) {
		return "OK".equals(sourceAvailability.get(source));
	}

	private void sampleLoop() {
		while (running.get() && samples.size() < MAX_SAMPLES) {
			try {
				String sample = takeSample();
				if (sample != null) {
					samples.add(sample);
				}
				Thread.sleep(intervalMs);
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				return;
			} catch (RuntimeException unavailable) {
				samples.add("표본 채취 실패: " + rootMessage(unavailable));
				return;
			}
		}
	}

	private String takeSample() {
		List<Map<String, Object>> waits = usable("performance_schema.data_lock_waits")
				? jdbcTemplate.queryForList("select * from performance_schema.data_lock_waits")
				: List.of();
		List<Map<String, Object>> transactions = usable("information_schema.innodb_trx")
				? jdbcTemplate.queryForList("""
						select trx_id, trx_state, trx_started, trx_wait_started, trx_rows_locked,
						       trx_rows_modified, trx_isolation_level, left(trx_query, 200) as trx_query
						  from information_schema.innodb_trx
						""")
				: List.of();
		boolean waiting = !waits.isEmpty()
				|| transactions.stream().anyMatch(row -> row.get("trx_wait_started") != null);
		if (!waiting) {
			return null;
		}
		StringBuilder text = new StringBuilder();
		text.append("--- ").append(java.time.LocalDateTime.now()).append(" ---\n");
		if (!waits.isEmpty()) {
			text.append("[performance_schema.data_lock_waits]\n");
			waits.forEach(row -> text.append(row).append("\n"));
		}
		if (!transactions.isEmpty()) {
			text.append("[information_schema.INNODB_TRX]\n");
			transactions.forEach(row -> text.append(row).append("\n"));
		}
		String innodbStatus = innodbStatusExcerpt();
		if (innodbStatus != null) {
			text.append("[SHOW ENGINE INNODB STATUS 발췌]\n").append(innodbStatus).append("\n");
		}
		return text.toString();
	}

	/** 앞의 두 출처가 모두 막혔을 때의 마지막 수단. 전문은 길어서 앞부분만 싣는다. */
	private String innodbStatusExcerpt() {
		if (!usable("show engine innodb status")
				|| usable("performance_schema.data_lock_waits")
				|| usable("information_schema.innodb_trx")) {
			return null;
		}
		List<Map<String, Object>> rows = jdbcTemplate.queryForList("show engine innodb status");
		if (rows.isEmpty()) {
			return null;
		}
		String status = String.valueOf(rows.getFirst().get("Status"));
		return status.length() <= INNODB_STATUS_EXCERPT ? status : status.substring(0, INNODB_STATUS_EXCERPT);
	}

	private Map<String, Long> readStatus() {
		Map<String, Long> status = new LinkedHashMap<>();
		for (Map<String, Object> row : jdbcTemplate.queryForList(
				"show global status like 'Innodb_row_lock%'")) {
			String name = String.valueOf(row.get("Variable_name"));
			String value = String.valueOf(row.get("Value"));
			try {
				status.put(name, Long.parseLong(value));
			} catch (NumberFormatException notANumber) {
				status.put(name, 0L);
			}
		}
		return status;
	}

	private static String rootMessage(Throwable failure) {
		Throwable cause = failure;
		while (cause.getCause() != null && cause.getCause() != cause) {
			cause = cause.getCause();
		}
		return cause.getMessage();
	}
}
