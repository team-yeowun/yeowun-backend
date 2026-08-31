package modi.backend.ingestionv2.lab.outbox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * lab 하네스의 실행 파라미터 해석기 - 실험자가 규모·반복 수·출력 경로를 밖에서 조절하는 유일한 창구.
 *
 * <ul>
 *   <li>해석 순서: 시스템 프로퍼티 -> 환경변수 -> 프로젝트 루트 {@code lab-outbox.properties} -> 기본값</li>
 *   <li>파일을 1순위로 두지 않는 이유는 IDE 개별 실행에서 -D 가 더 편해서다</li>
 *   <li>파일 경로를 두는 이유는 Gradle Test 태스크가 CLI 의 -D 를 포크된 테스트 JVM 으로 전달하지 않기 때문이다
 *       (build.gradle 을 고치지 않는다는 제약이 있어 프로퍼티 파일이 유일하게 확실한 경로)</li>
 *   <li>기본값은 계획서 값 그대로 - 아무 설정 없이 돌리면 본측정이 된다</li>
 * </ul>
 */
final class OutboxLabConfig {

	/** 실험자가 값을 바꿔 넣는 파일. 없으면 전부 기본값. */
	static final Path FILE = Path.of("lab-outbox.properties");

	private static final Properties FILE_PROPERTIES = load();

	private OutboxLabConfig() {
	}

	private static Properties load() {
		Properties properties = new Properties();
		if (!Files.exists(FILE)) {
			return properties;
		}
		try (var reader = Files.newBufferedReader(FILE)) {
			properties.load(reader);
		} catch (IOException unreadable) {
			throw new UncheckedIOException("lab-outbox.properties 를 읽지 못했습니다.", unreadable);
		}
		return properties;
	}

	static String get(String key, String defaultValue) {
		String systemProperty = System.getProperty("lab.outbox." + key);
		if (systemProperty != null && !systemProperty.isBlank()) {
			return systemProperty.trim();
		}
		String environment = System.getenv("LAB_OUTBOX_" + key.toUpperCase(Locale.ROOT).replace('.', '_')
				.replace('-', '_'));
		if (environment != null && !environment.isBlank()) {
			return environment.trim();
		}
		String fromFile = FILE_PROPERTIES.getProperty("lab.outbox." + key);
		if (fromFile != null && !fromFile.isBlank()) {
			return fromFile.trim();
		}
		return defaultValue;
	}

	static int getInt(String key, int defaultValue) {
		return Integer.parseInt(get(key, Integer.toString(defaultValue)));
	}

	static boolean getBoolean(String key, boolean defaultValue) {
		return Boolean.parseBoolean(get(key, Boolean.toString(defaultValue)));
	}

	/** 본측정 반복 수(워밍업 제외). 계획서 F-07 로 step-01·04·05 는 10. */
	static int runs() {
		return getInt("n", 10);
	}

	/** 워밍업 횟수 - 집계에서 빠진다. */
	static int warmup() {
		return getInt("warmup", 1);
	}

	/** 이번 실행이 오를 규모 사다리. 기본은 필수 3점, 실험자가 S3 를 덧붙인다. */
	static List<OutboxLabScale> scales() {
		List<OutboxLabScale> selected = new ArrayList<>();
		for (String token : get("scales", "S0,S1,S2").split(",")) {
			selected.add(OutboxLabScale.valueOf(token.trim().toUpperCase(Locale.ROOT)));
		}
		return List.copyOf(selected);
	}

	/** 원시 파일이 쌓이는 뿌리. 기본은 아웃박스 인덱스 실험 문서 폴더의 _workspace/03_measurer_raw. */
	static Path rawRoot() {
		return Path.of(get("rawRoot", "docs/전시수집_파이프라인_v2문서/AI-Driven-Development/problem/"
				+ "03-Outbox 폴링 쿼리 지연으로 인한 파이프라인 전체 지연 해소/_workspace/03_measurer_raw"));
	}

	/** 측정이 끝난 뒤 테이블을 비울지. 기본 true - 공유 컨테이너를 다음 클래스에 깨끗이 넘긴다. */
	static boolean truncateAfterClass() {
		return getBoolean("truncateAfterClass", true);
	}

	/** 이번 실행의 설정을 원시 파일 조건에 그대로 적기 위한 문자열. */
	static String describe() {
		return "n=%d warmup=%d scales=%s rawRoot=%s truncateAfterClass=%s configFile=%s"
				.formatted(runs(), warmup(), scales(), rawRoot(), truncateAfterClass(),
						Files.exists(FILE) ? FILE.toAbsolutePath() : "(없음 - 전부 기본값)");
	}
}
