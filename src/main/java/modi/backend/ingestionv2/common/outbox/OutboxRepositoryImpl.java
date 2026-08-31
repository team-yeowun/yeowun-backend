package modi.backend.ingestionv2.common.outbox;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;
import modi.backend.ingestionv2.common.IngestionErrorCode;
import modi.backend.support.error.CoreException;

/**
 * 아웃박스 포트의 JPA 어댑터.
 *
 * <ul>
 *   <li>선점 전략 분기가 여기 하나 - 포트도 서비스도 잠금 절을 모른다</li>
 *   <li>복제본 조회는 식별자만 받아 와 원본에서 엔티티를 읽는다 - 표시(UPDATE)가 원본 트랜잭션에 남아야 하므로</li>
 *   <li>복제본 경로가 없는 환경에서 REPLICA 를 고르면 설정 오류로 세운다 - 조용히 원본으로 돌아가면
 *       "복제본으로 보냈다"는 조건 기록이 거짓이 된다</li>
 * </ul>
 */
@Repository
@RequiredArgsConstructor
public class OutboxRepositoryImpl implements OutboxRepository {

	private final OutboxJpaRepository outboxJpaRepository;
	private final ObjectProvider<OutboxReplicaReader> replicaReader;

	@Override
	public Outbox save(Outbox outbox) {
		return outboxJpaRepository.save(outbox);
	}

	@Override
	public Optional<Outbox> findById(long id) {
		return outboxJpaRepository.findById(id);
	}

	@Override
	public List<Outbox> claimPending(int limit, OutboxClaimStrategy strategy, OutboxReadSource readSource) {
		if (readSource == OutboxReadSource.REPLICA) {
			return claimFromReplica(limit, strategy);
		}
		boolean unlimited = limit <= 0;
		return switch (strategy) {
			case NONE, REDIS_MARKER -> unlimited
					? outboxJpaRepository.selectAllPending()
					: outboxJpaRepository.selectPending(limit);
			case PESSIMISTIC -> unlimited
					? outboxJpaRepository.claimAllPendingForUpdate()
					: outboxJpaRepository.claimPendingForUpdate(limit);
			case SKIP_LOCKED -> unlimited
					? outboxJpaRepository.claimAllPending()
					: outboxJpaRepository.claimPending(limit);
		};
	}

	private List<Outbox> claimFromReplica(int limit, OutboxClaimStrategy strategy) {
		OutboxReplicaReader reader = replicaReader.getIfAvailable();
		if (reader == null) {
			throw new CoreException(IngestionErrorCode.OUTBOX_READ_SOURCE_UNAVAILABLE,
					"복제본 조회를 선택했지만 복제본 데이터소스가 없습니다. app.datasource.replica.url 을 확인하십시오.");
		}
		List<Long> ids = reader.readPendingIds(Math.max(limit, 0), strategy);
		return ids.isEmpty() ? List.of() : outboxJpaRepository.findAllById(ids);
	}

	@Override
	public List<Outbox> findFailed(int limit) {
		return outboxJpaRepository.findByStatusOrderByCreatedAtAscIdAsc(OutboxStatus.FAILED, PageRequest.of(0, limit));
	}

	@Override
	public long countPending() {
		return outboxJpaRepository.countByStatus(OutboxStatus.PENDING);
	}

	@Override
	public List<Outbox> findSentBefore(LocalDateTime threshold, int limit) {
		return outboxJpaRepository.findByStatusAndCreatedAtBeforeOrderByCreatedAtAscIdAsc(OutboxStatus.SENT,
				threshold, PageRequest.of(0, limit));
	}

	@Override
	public void deleteAll(List<Outbox> outboxes) {
		outboxJpaRepository.deleteAll(outboxes);
	}
}
