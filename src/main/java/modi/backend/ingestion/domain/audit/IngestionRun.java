package modi.backend.ingestion.domain.audit;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import modi.backend.ingestion.domain.SyncTrigger;

/**
 * 한 번의 카탈로그 동기화 실행 기록(append-only) — {@code ingestion_run} 매핑. <b>슬림 스키마</b>(설계 §5-5).
 *
 * <p>남긴 것: <b>왜 돌았고(trigger) 언제 돌았고(started/finished) 얼마나 모아서(collected) 몇 건을 새로
 * 스테이징했나(inserted)</b>. 그 외 집계(갱신·스킵·연기·원천 총계)는 삭제됐다 — 아이템 단위 사실은
 * 진행 상태({@code exhibition_progress})와 아웃박스가 더 정확히 안다(대시보드 2층 구조: run 요약 → 아이템 상세).
 *
 * <p>{@code finished_at}은 "목록 스테이징 루프가 끝난 시각"이다 — 아웃박스 완료가 아니다. "전체 파이프라인
 * 완료 시각"이라는 개념은 두지 않는다(전시별 완료는 progress.status의 몫).
 */
@Entity
@Table(name = "ingestion_run")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IngestionRun {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** 이 실행을 촉발한 계기(BOOT/SCHEDULE/MANUAL) — "왜 이 시각에 돌았나". */
	@Enumerated(EnumType.STRING)
	@Column(name = "trigger_type", nullable = false, length = 20)
	private SyncTrigger triggerType;

	@Column(name = "started_at")
	private LocalDateTime startedAt;

	/** 목록 스테이징 루프 종료 시각(아웃박스 완료 아님). */
	@Column(name = "finished_at")
	private LocalDateTime finishedAt;

	/** 이번 실행이 원천에서 모은 적재 가능 아이템 수. */
	@Column(name = "collected", nullable = false)
	private int collected;

	/** 새로 스테이징된 진행 행 수(신규 발견). */
	@Column(name = "inserted", nullable = false)
	private int inserted;

	private IngestionRun(SyncTrigger triggerType, LocalDateTime startedAt) {
		this.triggerType = triggerType;
		this.startedAt = startedAt;
	}

	/** 실행 시작 — 수집 결과를 받기 전 상태. 계기(trigger)를 함께 남긴다. */
	public static IngestionRun started(SyncTrigger triggerType, LocalDateTime startedAt) {
		return new IngestionRun(triggerType, startedAt);
	}

	/** 수집 건수를 기록한다. */
	public void fetched(int collected) {
		this.collected = collected;
	}

	/** 새 진행 행 스테이징 1건 누적. */
	public void recordStaged() {
		this.inserted++;
	}

	/** 종료 시각을 기록한다. */
	public void finished(LocalDateTime finishedAt) {
		this.finishedAt = finishedAt;
	}

	/** 이번 실행에 볼 게 있었나 — 요약 로그 발화 조건(전부 0이면 조용히 지나간다). */
	public boolean hasActivity() {
		return collected > 0 || inserted > 0;
	}
}
