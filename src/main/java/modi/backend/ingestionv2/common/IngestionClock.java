package modi.backend.ingestionv2.common;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * 수집 시각의 기준 - 한국 시간 고정.
 *
 * <ul>
 *   <li>회차 경계가 선점 마크의 구성 요소라 기준이 흔들리면 같은 회차가 둘로 갈림</li>
 *   <li>JVM 기본 타임존에 기대지 않음 - 배포 환경에서 UTC로 뜰 수 있음</li>
 *   <li>대상이 국내 전시라 원천의 날짜 감각도 한국 시간</li>
 * </ul>
 */
public final class IngestionClock {

	public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

	private IngestionClock() {
	}

	/** 오늘의 회차 날짜. */
	public static LocalDate today() {
		return LocalDate.now(ZONE);
	}

	/**
	 * 기록 시각.
	 *
	 * <ul>
	 *   <li>마이크로초 절삭 - 이 시각은 DATETIME(6) 컬럼과 payload JSON 양쪽에 실리는데,
	 *       리눅스 JVM은 나노초를 주므로 절삭 없이는 DB 왕복 후 두 값이 어긋남</li>
	 * </ul>
	 */
	public static LocalDateTime now() {
		return LocalDateTime.now(ZONE).truncatedTo(ChronoUnit.MICROS);
	}
}
