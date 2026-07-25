package modi.backend.ingestion.domain;

import modi.backend.ingestion.domain.audit.IngestionRun;

/**
 * 카탈로그 동기화 실행({@link IngestionRun})을 촉발한 계기 — {@code sync_run.trigger_type}.
 *
 * <p>같은 syncCatalog라도 <b>무엇이 불렀나</b>가 운영 판독에 중요하다: 부팅 재시작이 잦아 BOOT 런이 몰리는지,
 * 정기 SCHEDULE이 도는지, 운영자가 손으로 MANUAL을 돌렸는지. 현행은 이 계기를 남기지 않아 sync_run 추이만으론
 * "왜 이 시각에 돌았나"를 알 수 없다.
 */
public enum SyncTrigger {

	/** 애플리케이션 부팅 시 1회 동기화(cold start 방지). */
	BOOT,

	/** 정기 스케줄(기본 매일 자정). */
	SCHEDULE,

	/** 운영자 수동 트리거(관리 콘솔 등). */
	MANUAL
}
