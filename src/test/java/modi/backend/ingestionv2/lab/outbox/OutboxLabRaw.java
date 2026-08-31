package modi.backend.ingestionv2.lab.outbox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.json.JsonMapper;

/**
 * 원시 파일 기록기 - 프로토콜 §5 스키마를 그대로 쓴다.
 *
 * <ul>
 *   <li>한 스텝 x 한 변형(before/after)마다 폴더 하나: {@code step-NN/{before|after}/}</li>
 *   <li>{@code run-summary.json} 은 §5 객체들의 색인 - {@code series[]} 원소 하나하나가 §5 객체 그대로다
 *       (규모 x PENDING 조건 x 지표의 행렬이라 파일 하나에 단일 객체로는 담기지 않는다)</li>
 *   <li>{@code series/*.json} 에 같은 객체를 낱개로도 떨어뜨린다 - 감사자가 한 계열만 떼어 볼 때 쓴다</li>
 *   <li>{@code run-summary.md} 는 사람이 읽는 표, 나머지는 첨부(EXPLAIN·SHOW INDEX·스케줄러 빈 출력) 원문</li>
 * </ul>
 */
final class OutboxLabRaw {

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	private final Path directory;
	private final String step;
	private final String variant;
	private final List<Map<String, Object>> series = new ArrayList<>();
	private final List<String> attachments = new ArrayList<>();
	private final List<String> notes = new ArrayList<>();
	private Map<String, Object> baseCondition = new LinkedHashMap<>();

	OutboxLabRaw(String step, String variant) {
		this.step = step;
		this.variant = variant;
		this.directory = OutboxLabConfig.rawRoot().resolve(step).resolve(variant);
		try {
			Files.createDirectories(directory.resolve("series"));
		} catch (IOException notWritable) {
			throw new UncheckedIOException("원시 파일 폴더를 만들지 못했습니다. " + directory, notWritable);
		}
	}

	void baseCondition(Map<String, Object> condition) {
		this.baseCondition = new LinkedHashMap<>(condition);
	}

	void note(String note) {
		notes.add(note);
	}

	/** 반복 측정 한 계열. values 는 워밍업을 뺀 본측정값이다. */
	void series(String name, String metric, String unit, double[] values, Map<String, Object> extraCondition) {
		OutboxLabStats stats = OutboxLabStats.of(values);
		List<Map<String, Object>> runs = new ArrayList<>();
		for (int index = 0; index < values.length; index++) {
			Map<String, Object> run = new LinkedHashMap<>();
			run.put("i", index + 1);
			run.put("value", OutboxLabStats.round(values[index]));
			run.put("failed", false);
			runs.add(run);
		}
		Map<String, Object> condition = new LinkedHashMap<>(baseCondition);
		condition.putAll(extraCondition);

		Map<String, Object> object = new LinkedHashMap<>();
		object.put("step", step);
		object.put("variant", variant);
		object.put("series", name);
		object.put("metric", metric);
		object.put("unit", unit);
		object.put("aggregation", stats.toAggregation());
		object.put("runs", runs);
		object.put("warmup_excluded", OutboxLabConfig.warmup());
		object.put("condition", condition);
		object.put("attachments", List.copyOf(attachments));
		series.add(object);
		write(directory.resolve("series").resolve(name + ".json"), toJson(object));
	}

	/** 반복이 없는 단발 관측(삭제 행수·집합 비교 등)도 같은 폴더에 남긴다. */
	void observation(String name, Map<String, Object> payload) {
		Map<String, Object> object = new LinkedHashMap<>();
		object.put("step", step);
		object.put("variant", variant);
		object.put("observation", name);
		object.put("condition", baseCondition);
		object.putAll(payload);
		series.add(object);
		write(directory.resolve("series").resolve(name + ".json"), toJson(object));
	}

	void attach(String fileName, String content) {
		write(directory.resolve(fileName), content);
		attachments.add(fileName);
	}

	void finish() {
		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("step", step);
		summary.put("variant", variant);
		summary.put("condition", baseCondition);
		summary.put("notes", notes);
		summary.put("attachments", attachments);
		summary.put("series", series);
		write(directory.resolve("run-summary.json"), toJson(summary));
		write(directory.resolve("run-summary.md"), markdown());
	}

	Path directory() {
		return directory;
	}

	private String markdown() {
		StringBuilder text = new StringBuilder();
		text.append("# ").append(step).append(" / ").append(variant).append("\n\n");
		text.append("> 이 파일은 하네스가 자동 생성한다. 손으로 고치지 말 것 - 고치면 원시값이 아니게 된다.\n\n");
		text.append("## 조건\n\n| 항목 | 값 |\n|---|---|\n");
		baseCondition.forEach((key, value) -> text.append("| `").append(key).append("` | ")
				.append(inline(value)).append(" |\n"));
		text.append("\n## 계열\n\n");
		text.append("| 계열 | 지표 | 단위 | N | p50 | p95 | max | stdev | 규모 | PENDING |\n");
		text.append("|---|---|---|---|---|---|---|---|---|---|\n");
		for (Map<String, Object> object : series) {
			if (!object.containsKey("aggregation")) {
				continue;
			}
			@SuppressWarnings("unchecked")
			Map<String, Object> aggregation = (Map<String, Object>) object.get("aggregation");
			@SuppressWarnings("unchecked")
			Map<String, Object> condition = (Map<String, Object>) object.get("condition");
			text.append("| ").append(object.get("series"))
					.append(" | ").append(object.get("metric"))
					.append(" | ").append(object.get("unit"))
					.append(" | ").append(aggregation.get("n"))
					.append(" | ").append(aggregation.get("p50"))
					.append(" | ").append(aggregation.get("p95"))
					.append(" | ").append(aggregation.get("max"))
					.append(" | ").append(aggregation.get("stdev"))
					.append(" | ").append(condition.get("rows"))
					.append(" | ").append(condition.get("pending_rows"))
					.append(" |\n");
		}
		text.append("\n- p95 산출: ").append(OutboxLabStats.P95_METHOD).append("\n");
		List<Map<String, Object>> observations = series.stream()
				.filter(object -> object.containsKey("observation")).toList();
		if (!observations.isEmpty()) {
			text.append("\n## 단발 관측\n\n");
			for (Map<String, Object> object : observations) {
				text.append("### ").append(object.get("observation")).append("\n\n");
				object.forEach((key, value) -> {
					if (List.of("step", "variant", "observation", "condition").contains(key)) {
						return;
					}
					text.append("- `").append(key).append("`: ").append(inline(value)).append("\n");
				});
				text.append("\n");
			}
		}
		if (!notes.isEmpty()) {
			text.append("\n## 메모\n\n");
			notes.forEach(note -> text.append("- ").append(note).append("\n"));
		}
		if (!attachments.isEmpty()) {
			text.append("\n## 첨부\n\n");
			attachments.forEach(name -> text.append("- `").append(name).append("`\n"));
		}
		return text.toString();
	}

	private static String inline(Object value) {
		String text = String.valueOf(value).replace("|", "\\|");
		return text.contains("\n") ? "<pre>" + text.replace("\n", "<br>") + "</pre>" : text;
	}

	private static String toJson(Object value) {
		return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n";
	}

	private static void write(Path path, String content) {
		try {
			Files.createDirectories(path.getParent());
			Files.writeString(path, content, StandardCharsets.UTF_8);
		} catch (IOException notWritable) {
			throw new UncheckedIOException("원시 파일을 쓰지 못했습니다. " + path, notWritable);
		}
	}
}
