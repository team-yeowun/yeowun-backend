package modi.backend.ingestionv2.enrich.domain;

/** 보강 유스케이스 입력. */
public final class EnrichCriteria {

	private EnrichCriteria() {
	}

	/** 실패 목록 조회 조건. 상태는 FAILED 로 고정이라 조건에 넣지 않는다. */
	public record FailedSearch(int page, int size) {
	}
}
