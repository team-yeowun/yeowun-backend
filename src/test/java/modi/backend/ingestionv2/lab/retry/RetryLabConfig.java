package modi.backend.ingestionv2.lab.retry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * 재시도·DLQ 실험 하네스의 실행 파라미터 해석기 - 실험자가 규모·반복 수·변형·출력 경로를 밖에서 조절하는 창구.
 *
 * <ul>
 *   <li>해석 순서: 시스템 프로퍼티 -> 환경변수 -> 프로젝트 루트 {@code lab-retry.properties} -> 기본값</li>
 *   <li>파일 경로를 두는 이유는 Gradle Test 태스크가 CLI 의 {@code -D} 를 포크된 테스트 JVM 으로 넘기지 않기 때문
 *       (build.gradle 을 고치지 않는 제약이 있어 프로퍼티 파일이 유일하게 확실한 경로)</li>
 *   <li>아웃박스 실험의 {@code lab-outbox.properties} 와 키 접두사가 다르다 - 두 실험이 같은 워크트리에서 동시에 돈다</li>
 *   <li>기본값은 계획서 값 그대로 - 아무 설정 없이 돌리면 본측정이 된다</li>
 * </ul>
 */
final class RetryLabConfig {

	/** 실험자가 값을 바꿔 넣는 파일. 없으면 전부 기본값. */
	static final Path FILE = Path.of("lab-retry.properties");

	private static final String PREFIX = "lab.retry.";

	private static final Properties FILE_PROPERTIES = load();

	private RetryLabConfig() {
	}

	private static Properties load() {
		Properties properties = new Properties();
		if (!Files.exists(FILE)) {
			return properties;
		}
		try (var reader = Files.newBufferedReader(FILE)) {
			properties.load(reader);
		} catch (IOException unreadable) {
			throw new UncheckedIOException("lab-retry.properties 를 읽지 못했습니다.", unreadable);
		}
		return properties;
	}

	static String get(String key, String defaultValue) {
		String systemProperty = System.getProperty(PREFIX + key);
		if (systemProperty != null && !systemProperty.isBlank()) {
			return systemProperty.trim();
		}
		String environment = System.getenv("LAB_RETRY_"
				+ key.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_'));
		if (environment != null && !environment.isBlank()) {
			return environment.trim();
		}
		String fromFile = FILE_PROPERTIES.getProperty(PREFIX + key);
		if (fromFile != null && !fromFile.isBlank()) {
			return fromFile.trim();
		}
		return defaultValue;
	}

	static int getInt(String key, int defaultValue) {
		return Integer.parseInt(get(key, Integer.toString(defaultValue)));
	}

	static long getLong(String key, long defaultValue) {
		return Long.parseLong(get(key, Long.toString(defaultValue)));
	}

	static boolean getBoolean(String key, boolean defaultValue) {
		return Boolean.parseBoolean(get(key, Boolean.toString(defaultValue)));
	}

	private static List<Integer> intList(String key, String defaultValue) {
		List<Integer> values = new ArrayList<>();
		for (String token : get(key, defaultValue).split(",")) {
			values.add(Integer.parseInt(token.trim()));
		}
		return List.copyOf(values);
	}

	/** 본측정 반복 수(워밍업 제외). 계획서 step-07 은 N=5. */
	static int runs() {
		return getInt("n", 5);
	}

	/** 워밍업 횟수 - 집계에서 빠진다. */
	static int warmup() {
		return getInt("warmup", 1);
	}

	// --- step-07 재처리 동시성 ---

	/** 한 run 이 만드는 격리 행 수. 계획서 200. */
	static int redriveRows() {
		return getInt("redrive.rows", 200);
	}

	/** 행 하나에 동시 출발하는 스레드 수. 실험 범위 결정으로 50 하나만 잰다(10 폐기 - 기울기 지표도 함께 폐기). */
	static List<Integer> redriveThreads() {
		return intList("redrive.threads", "50");
	}

	/**
	 * 이번 실행이 돌릴 변형.
	 *
	 * <ul>
	 *   <li>Phase A 는 {@code P0} 하나 - {@code @Version} 마이그레이션 이전 커밋에서 돈다</li>
	 *   <li>Phase B 는 {@code V1,V2,V3} - 마이그레이션 직후에 붙여서 돈다</li>
	 *   <li>기본값을 Phase A 로 두는 이유는 순서를 틀리면 되돌릴 수 없기 때문이다</li>
	 * </ul>
	 */
	static List<RedriveVariant> redriveVariants() {
		List<RedriveVariant> selected = new ArrayList<>();
		for (String token : get("redrive.variants", "P0").split(",")) {
			selected.add(RedriveVariant.valueOf(token.trim().toUpperCase(Locale.ROOT)));
		}
		return List.copyOf(selected);
	}

	/** 잠금 대기 표본 채취 주기. 0 이면 채취하지 않는다. */
	static long lockSampleIntervalMs() {
		return getLong("redrive.lockSampleMs", 20L);
	}

	// --- step-04 소비 재전달 백오프 ---

	/** 스트림에 적재할 메시지 수. 실험 범위 결정으로 주 규모 10,000 하나만 잰다(보조 1,000 폐기). */
	static int reclaimRows() {
		return getInt("reclaim.rows", 10_000);
	}

	/** 관측창 길이(초). 계획서 60s. */
	static int reclaimWindowSeconds() {
		return getInt("reclaim.windowSeconds", 60);
	}

	/** 회수 드라이버가 틱을 미는 주기(ms). 계획서 1s. */
	static long reclaimTickMs() {
		return getLong("reclaim.tickMs", 1_000L);
	}

	/** 조건 A 의 전달당 실패율(%). 계획서 30. */
	static int reclaimFailurePercent() {
		return getInt("reclaim.failurePercent", 30);
	}

	/** 조건 B 의 장애창 길이(초) - 이 구간은 100% 실패. 계획서 20s. */
	static int reclaimOutageSeconds() {
		return getInt("reclaim.outageSeconds", 20);
	}

	/** 실패 주입 난수 시드 - 변형 간 같은 실패 패턴을 쓰기 위해 고정한다. */
	static long reclaimFaultSeed() {
		return getLong("reclaim.faultSeed", 20260829L);
	}

	// --- step-06 DLQ 관리 조회 ---

	/** 격리 테이블 규모 사다리. 계획서 10,000 과 100,000 - 규모 2점은 추세를 보기 위한 최소치다. */
	static List<Integer> deadLetterScales() {
		return intList("dlq.scales", "10000,100000");
	}

	/** 관리자 목록 조회 상한 사다리. 계획서 50 과 200. */
	static List<Integer> deadLetterLimits() {
		return intList("dlq.limits", "50,200");
	}

	/** run 하나가 같은 조회를 몇 번 반복하는가. 계획서 30. */
	static int deadLetterRepeats() {
		return getInt("dlq.repeats", 30);
	}

	/** 인덱스 없는 조건까지 잴지. 끄면 인덱스 있는 조건만 돈다(공유 컨테이너 보호용 비상 스위치). */
	static boolean deadLetterMeasureWithoutIndex() {
		return getBoolean("dlq.withoutIndex", true);
	}

	// --- 공통 ---

	/** 원시 파일이 쌓이는 뿌리. 기본은 재시도·DLQ 실험 문서 폴더의 _workspace/03_measurer_raw. */
	static Path rawRoot() {
		return Path.of(get("rawRoot", "docs/전시수집_파이프라인_v2문서/AI-Driven-Development/problem/"
				+ "04-재시도 전략 개선과 DLQ 격리 체계 구축/_workspace/03_measurer_raw"));
	}

	/** 측정이 끝난 뒤 슬라이스 테이블을 비울지. 기본 true - 공유 컨테이너를 다음 클래스에 깨끗이 넘긴다. */
	static boolean truncateAfterClass() {
		return getBoolean("truncateAfterClass", true);
	}

	/** 이번 실행의 설정을 원시 파일 조건에 그대로 적기 위한 문자열. */
	static String describe() {
		return ("n=%d warmup=%d redrive.rows=%d redrive.threads=%s redrive.variants=%s reclaim.rows=%d "
				+ "reclaim.windowSeconds=%d reclaim.tickMs=%d reclaim.failurePercent=%d reclaim.outageSeconds=%d "
				+ "reclaim.faultSeed=%d rawRoot=%s truncateAfterClass=%s configFile=%s")
				.formatted(runs(), warmup(), redriveRows(), redriveThreads(), redriveVariants(), reclaimRows(),
						reclaimWindowSeconds(), reclaimTickMs(), reclaimFailurePercent(), reclaimOutageSeconds(),
						reclaimFaultSeed(), rawRoot(), truncateAfterClass(),
						Files.exists(FILE) ? FILE.toAbsolutePath() : "(없음 - 전부 기본값)");
	}
}
