package modi.backend.ingestionv2.collect.domain;

/** 수집 회차 실행 상태. 실패한 실행과 lease가 만료된 실행만 같은 날짜로 다시 선점할 수 있다. */
public enum CollectBatchStatus {
	RUNNING,
	COMPLETED,
	FAILED
}
