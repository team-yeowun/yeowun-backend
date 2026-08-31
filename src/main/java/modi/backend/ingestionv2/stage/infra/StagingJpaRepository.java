package modi.backend.ingestionv2.stage.infra;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import modi.backend.ingestionv2.stage.domain.Staging;
import modi.backend.ingestionv2.stage.domain.StagingStatus;

/** Spring Data 계약. 잠금 조회만 명시 쿼리로 둔다. */
public interface StagingJpaRepository extends JpaRepository<Staging, Long> {

	Optional<Staging> findByVendorKey(String vendorKey);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select s from Staging s where s.vendorKey = :vendorKey")
	Optional<Staging> findByVendorKeyForUpdate(@Param("vendorKey") String vendorKey);

	/** 관리자 실패 목록. idx_ingestion_staging_status_updated_at 을 그대로 타도록 조건과 정렬을 맞춘다. */
	List<Staging> findByStatusOrderByUpdatedAtDesc(StagingStatus status, Pageable pageable);

	long countByStatus(StagingStatus status);
}
