package modi.backend.ingestionv2.collect.domain;

import java.time.LocalDate;

/** 수집 유스케이스 입력. */
public final class CollectCriteria {

	private CollectCriteria() {
	}

	/** 회차 지정 수집. 스케줄러는 오늘 날짜를, 관리자는 임의 날짜를 넣는다. */
	public record Batch(LocalDate batchDate) {

		public static Batch of(LocalDate batchDate) {
			return new Batch(batchDate);
		}
	}
}
