package modi.backend.ingestion.domain.outbox;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 전시 아웃박스 저장 포트(Spring 무의존).
 *
 * <p>선별({@link #findDue})과 멱등 enqueue 조회({@link #findByMessageTypeAndTargetKey})가 이 큐의 두 축이다.
 * 필터 조건(어떤 status가 선별 대상인가)은 구현 세부라 어댑터가 주입한다 — 포트는 도메인 언어만 노출한다.
 */
public interface OutboxMessageRepository {

	OutboxMessage save(OutboxMessage job);

	/** 멱등 enqueue용 — 같은 (종류, 대상)의 기존 작업을 찾는다(UK로 최대 1건). */
	Optional<OutboxMessage> findByMessageTypeAndTargetKey(IngestionEventType messageType, String targetKey);

	/**
	 * 선별 — 미종료(PENDING·FAILED_RETRYABLE)이고 재시도 시각이 도래한({@code next_attempt_at <= now}) 작업을
	 * 도래 순으로 최대 {@code limit}건 조회한다. 종류 필터가 있으면 그 종류만 본다.
	 */
	List<OutboxMessage> findDue(IngestionEventType messageType, LocalDateTime now, int limit);

	/** 상태별 개수(운영 조회·테스트용 — 예: FAILED_PERMANENT 누적 감시). */
	long countByStatus(OutboxMessageStatus status);

	/**
	 * SUCCEEDED 정리 — 종료 시각이 {@code cutoff} 이전인 성공 행을 최대 {@code limit}건 삭제하고 삭제 수를
	 * 돌려준다. 소량 배치 전제(100만 건 실험 — 대량 일괄 삭제는 삭제 마크·통계 왜곡으로 일시 악화를 부른다).
	 * FAILED_PERMANENT는 지우지 않는다(관리자 감사·수동 재시도 대상).
	 */
	int purgeSucceededBefore(java.time.LocalDateTime cutoff, int limit);
}
