package modi.backend.ingestionv2.common.deadletter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 격리 포트 - 스프링 무의존.
 *
 * <ul>
 *   <li>목록 조회가 주된 용도 - 관리자가 훑고 원인을 확인함</li>
 *   <li>유입 건수 조회는 알람의 입력 - 0이 아니면 그 자체로 신호</li>
 *   <li>두 조회가 (status, failed_at) 인덱스 하나를 탄다</li>
 * </ul>
 */
public interface DeadLetterRepository {

	DeadLetter save(DeadLetter deadLetter);

	Optional<DeadLetter> findById(long id);

	/** 관리자가 아직 처리하지 않은 격리 항목 - 오래된 순. */
	List<DeadLetter> findPending(int limit);

	/** 기준 시각 이후의 유입 건수 - 알람 임계치 판단용. */
	long countFailedAfter(LocalDateTime threshold);
}
