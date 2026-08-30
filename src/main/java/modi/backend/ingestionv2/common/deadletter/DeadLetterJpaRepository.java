package modi.backend.ingestionv2.common.deadletter;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** 격리 스프링 데이터 리포지토리. */
public interface DeadLetterJpaRepository extends JpaRepository<DeadLetter, Long> {

	List<DeadLetter> findByStatusOrderByFailedAtAscIdAsc(DeadLetterStatus status, Pageable pageable);

	long countByFailedAtAfter(LocalDateTime threshold);
}
