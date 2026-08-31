package modi.backend.ingestionv2.common.outbox;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

/** 아웃박스 포트의 JPA 어댑터. */
@Repository
@RequiredArgsConstructor
public class OutboxRepositoryImpl implements OutboxRepository {

	private final OutboxJpaRepository outboxJpaRepository;

	@Override
	public Outbox save(Outbox outbox) {
		return outboxJpaRepository.save(outbox);
	}

	@Override
	public Optional<Outbox> findById(long id) {
		return outboxJpaRepository.findById(id);
	}

	@Override
	public List<Outbox> claimPending(int limit) {
		return outboxJpaRepository.claimPending(limit);
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
