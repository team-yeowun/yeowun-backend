package modi.backend.ingestion.application.admin;

import java.time.LocalDateTime;
import java.util.List;

import modi.backend.ingestion.domain.audit.IngestionRun;
import modi.backend.ingestion.domain.outbox.OutboxMessage;
import modi.backend.ingestion.domain.progress.ExhibitionProgress;

/**
 * 수집 파이프라인 관리자 대시보드의 조회 결과 어휘(설계 §7 — 2층 구조: 런 요약 → 아이템 상세).
 * 파일 1개당 1 record 금지 컨벤션에 따라 외곽 클래스에 중첩 record로 묶는다.
 */
public final class IngestionAdminResult {

	private IngestionAdminResult() {
	}

	/** 요약 카드 — 진행 상태·아웃박스의 상태별 카운트(대시보드 첫 화면). */
	public record Summary(
			long progressPending, long progressEnriching, long progressCompleted, long progressFailed,
			long outboxPending, long outboxRetryable, long outboxSucceeded, long outboxFailedPermanent) {
	}

	/** 런 요약 한 행 — "run당 무엇을 얼마나"(ingestion_run 슬림 스키마 그대로). */
	public record Run(Long id, String trigger, LocalDateTime startedAt, LocalDateTime finishedAt,
			int collected, int inserted) {

		public static Run from(IngestionRun run) {
			return new Run(run.getId(), run.getTriggerType().name(), run.getStartedAt(), run.getFinishedAt(),
					run.getCollected(), run.getInserted());
		}
	}

	/** 진행 상태 한 행 — 초기화 단계·막힌 사유·전시장 새/기존(설계 §7 컬럼). */
	public record Progress(Long id, String externalId, String status, String placeKey, String placeOutcome,
			LocalDateTime detailResolvedAt, LocalDateTime genreClassifiedAt, Long promotedExhibitionId,
			String lastError, LocalDateTime createdAt, LocalDateTime updatedAt) {

		public static Progress from(ExhibitionProgress p) {
			return new Progress(p.getId(), p.getExternalId(), p.getStatus().name(), p.getPlaceKey(),
					p.getPlaceOutcome() == null ? null : p.getPlaceOutcome().name(),
					p.getDetailResolvedAt(), p.getGenreClassifiedAt(), p.getPromotedExhibitionId(),
					p.getLastError(), p.getCreatedAt(), p.getUpdatedAt());
		}
	}

	/** 아웃박스 한 행 — 지금 재시도중·영구실패(설계 §7 컬럼). */
	public record Outbox(Long id, String eventType, String targetKey, String status, int attemptCount,
			LocalDateTime nextAttemptAt, String lastError, LocalDateTime updatedAt) {

		public static Outbox from(OutboxMessage m) {
			return new Outbox(m.getId(), m.getMessageType().name(), m.getTargetKey(), m.getStatus().name(),
					m.getAttemptCount(), m.getNextAttemptAt(), m.getLastError(), m.getUpdatedAt());
		}
	}

	/** 페이지 응답 공통 — Offset 페이지({@code page=0&size=20}) 컨벤션. */
	public record Page<T>(List<T> items, int page, int size, long totalElements, int totalPages) {
	}

	/** 정리 결과 — 몇 건을 어느 기준선 이전에서 지웠는지(주간 배치·수동 트리거 공용). */
	public record Purged(int deleted, LocalDateTime cutoff) {
	}

	/** 수동 재시도 결과 — 무엇이 되살아났는지(관리자 화면 피드백). */
	public record Retried(String target, boolean progressReopened, boolean messageReactivated, String nextStep) {
	}
}
