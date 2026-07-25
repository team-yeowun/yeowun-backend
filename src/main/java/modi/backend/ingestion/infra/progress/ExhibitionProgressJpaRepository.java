package modi.backend.ingestion.infra.progress;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import modi.backend.ingestion.domain.progress.ExhibitionProgress;
import modi.backend.ingestion.domain.progress.ProgressStatus;

/** Spring Data JPA — 진행 상태. 페이지·상태 질의는 관리자 대시보드용(파사드 직사용 허용 — 슬라이스 내부 실용). */
public interface ExhibitionProgressJpaRepository extends JpaRepository<ExhibitionProgress, Long> {

	Optional<ExhibitionProgress> findByExternalId(String externalId);

	List<ExhibitionProgress> findAllByPlaceKey(String placeKey);

	Page<ExhibitionProgress> findAllByStatus(ProgressStatus status, Pageable pageable);

	long countByStatus(ProgressStatus status);
}
