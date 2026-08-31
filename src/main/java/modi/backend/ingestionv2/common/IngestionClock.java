package modi.backend.ingestionv2.common;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

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

	/** 기록 시각. */
	public static LocalDateTime now() {
		return LocalDateTime.now(ZONE);
	}
}
