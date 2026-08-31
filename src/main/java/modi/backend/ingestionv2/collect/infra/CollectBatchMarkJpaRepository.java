package modi.backend.ingestionv2.collect.infra;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import modi.backend.ingestionv2.collect.domain.CollectBatchMark;

/**
 * 회차 마크 Spring Data 리포지토리.
 *
 * <ul>
 *   <li>조건부 삽입 한 문장 (조회 후 저장 사이의 경합 창을 없앰)</li>
 *   <li>반환값 1 = 선점 성공, 0 = 이미 선점됨 (예외 없음)</li>
 *   <li>INSERT IGNORE 는 MySQL 문법 - 이 결합은 이 메서드 안에 갇힌다</li>
 * </ul>
 */
public interface CollectBatchMarkJpaRepository extends JpaRepository<CollectBatchMark, Long> {

	@Modifying
	@Query(
			value = "INSERT IGNORE INTO ingestion_collect_batch_mark (batch_date, claimed_at) VALUES (:batchDate, :claimedAt)",
			nativeQuery = true)
	int insertIfAbsent(@Param("batchDate") LocalDate batchDate, @Param("claimedAt") LocalDateTime claimedAt);
}
