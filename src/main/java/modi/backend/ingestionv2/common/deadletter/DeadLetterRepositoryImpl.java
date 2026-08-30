package modi.backend.ingestionv2.common.deadletter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

/**
 * 격리 포트의 JPA 어댑터.
 *
 * <ul>
 *   <li>저장은 즉시 반영(flush) - 낙관적 잠금 충돌이 커밋 시점이 아니라 부른 자리에서 드러나야
 *       트랜잭션 소유자가 자기 어휘의 오류로 번역 가능</li>
 *   <li>커밋 시점 예외는 프록시 바깥에서 터져 서비스 메서드가 잡지 못함 - 번역 자리가 파사드나
 *       컨트롤러로 밀려나고 메시지가 흩어짐</li>
 * </ul>
 */
@Repository
@RequiredArgsConstructor
public class DeadLetterRepositoryImpl implements DeadLetterRepository {

	private final DeadLetterJpaRepository deadLetterJpaRepository;

	@Override
	public DeadLetter save(DeadLetter deadLetter) {
		return deadLetterJpaRepository.saveAndFlush(deadLetter);
	}

	@Override
	public Optional<DeadLetter> findById(long id) {
		return deadLetterJpaRepository.findById(id);
	}

	@Override
	public List<DeadLetter> findPending(int limit) {
		return deadLetterJpaRepository.findByStatusOrderByFailedAtAscIdAsc(DeadLetterStatus.PENDING,
				PageRequest.of(0, limit));
	}

	@Override
	public long countFailedAfter(LocalDateTime threshold) {
		return deadLetterJpaRepository.countByFailedAtAfter(threshold);
	}
}
