package modi.backend.ingestionv2.enrich.domain;

/** 실패 기록의 결과. 재시도 대상인지 격리 대상인지, 아니면 이미 끝난 건인지. */
public enum FailureOutcome {

	ALREADY_DONE,
	RETRY,
	EXHAUSTED
}
