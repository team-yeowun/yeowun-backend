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
}
