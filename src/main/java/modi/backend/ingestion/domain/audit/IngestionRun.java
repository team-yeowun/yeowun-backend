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
import modi.backend.ingestion.application.draft.ExhibitionDraftService.StageOutcome;
import modi.backend.ingestion.domain.SyncTrigger;

/**
 * 한 번의 카탈로그 동기화 실행 기록(append-only) — {@code ingestion_run} 매핑.
 *
 * <p><b>왜 {@code external_api_call_log}의 컬럼이 아니라 별도 테이블인가</b>: 여기 남는 건 <b>배치 단위 사실</b>이다.
 * 목록 3콜은 각자 200 OK로 성공하는데 "이번 실행이 몇 건을 수집했나"는 개별 호출 행의 {@code outcome}으로
 * 표현할 자리가 없다(그 호출은 SUCCESS다). 두 테이블의 역할이 갈린다 — {@code external_api_call_log}는
 * "무엇을 몇 번 불렀나·얼마를 태웠나", {@code ingestion_run}은 "이번 실행이 무엇을 얼마나 처리했나".
 *
 * <p>집계 값들은 {@code syncCatalog}가 이미 계산해 <b>로그로만 흘려보내던</b> 것이다. 로그는 질의할 수 없어
 * 추이도 회귀도 볼 수 없다 — 같은 값을 행으로 남긴다.
 *
 * <p><b>절단({@code truncated}) 컬럼은 제거됐다</b>(V42): 이 수집의 목표가 <b>신규 등록 포착</b>으로 좁혀졌고
 * (사용자 결정), 전량 정합을 전제하던 "원천을 다 가져왔나"라는 물음이 함께 사라졌다. {@code total_count}는
 * 남는다 — 원천 규모의 추이 자체는 여전히 볼 값이다.
 */
@Entity
@Table(name = "ingestion_run")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IngestionRun {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** 이 실행을 촉발한 계기(BOOT/SCHEDULE/MANUAL) — "왜 이 시각에 돌았나"를 남긴다. */
	@Enumerated(EnumType.STRING)
	@Column(name = "trigger_type", nullable = false, length = 20)
	private SyncTrigger triggerType;

	@Column(name = "started_at")
	private LocalDateTime startedAt;

	@Column(name = "finished_at")
	private LocalDateTime finishedAt;

	/** 원천이 말한 총 건수. 인증키 미설정 등으로 호출이 없었으면 null = "모른다"(0이 아니다). */
	@Column(name = "total_count")
	private Integer totalCount;

	@Column(name = "collected", nullable = false)
	private int collected;

	@Column(name = "inserted", nullable = false)
	private int inserted;

	@Column(name = "completed", nullable = false)
	private int completed;

	@Column(name = "skipped", nullable = false)
	private int skipped;

	@Column(name = "deferred", nullable = false)
	private int deferred;

	private IngestionRun(SyncTrigger triggerType, LocalDateTime startedAt) {
		this.triggerType = triggerType;
		this.startedAt = startedAt;
	}

	/** 실행 시작 — 수집 결과를 받기 전 상태. 계기(trigger)를 함께 남긴다. */
	public static IngestionRun started(SyncTrigger triggerType, LocalDateTime startedAt) {
		return new IngestionRun(triggerType, startedAt);
	}

	/** 수집 결과(원천이 말한 총 건수·수집 건수)를 기록한다. */
	public void fetched(Integer totalCount, int collected) {
		this.totalCount = totalCount;
		this.collected = collected;
	}

	/**
	 * 스테이징 한 건의 결과를 집계에 누적한다 — 카운팅 규칙은 이 엔티티의 행위다(파사드 지역변수 ❌).
	 * {@link StageOutcome}은 같은 수집 슬라이스의 내부 어휘라 도메인이 받는 것을 허용한다.
	 * SKIPPED(이미 완성/종료 — 변화 없음)는 어느 집계에도 잡히지 않는다.
	 */
	public void record(StageOutcome outcome) {
		switch (outcome) {
			case STAGED -> recordStaged();
			case REFRESHED -> recordRefreshed();
			case SKIPPED -> { /* 이미 완성/종료 — 변화 없음 */ }
			case DEFERRED -> recordDeferred();
		}
	}

	/** 새 draft 스테이징 1건 누적({@code inserted}). */
	public void recordStaged() {
		this.inserted++;
	}

	/** 기존 미종료 draft 갱신 1건 누적({@code completed} — 컬럼 불변, 의미는 "갱신"). */
	public void recordRefreshed() {
		this.completed++;
	}

	/** 기간 비정상(종료<시작) 스킵 1건 누적({@code skipped}). */
	public void recordPeriodSkipped() {
		this.skipped++;
	}

	/** 단건 실패 연기(다음 주기 재시도) 1건 누적({@code deferred}). */
	public void recordDeferred() {
		this.deferred++;
	}

	/** 종료 시각을 기록한다 — 집계는 루프 중 {@code recordXxx}로 이미 누적돼 있다. */
	public void finished(LocalDateTime finishedAt) {
		this.finishedAt = finishedAt;
	}

	/** 집계에 잡힌 건이 하나라도 있는가 — 요약 로그 발화 조건(전부 0이면 조용히 지나간다). */
	public boolean hasActivity() {
		return inserted > 0 || completed > 0 || skipped > 0 || deferred > 0;
	}
}
