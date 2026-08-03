package modi.backend.infra.exhibition.catalog;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import modi.backend.domain.exhibition.catalog.ExhibitionDetail;

/** Spring Data JPA — 전시 상세 satellite. */
public interface ExhibitionDetailJpaRepository extends JpaRepository<ExhibitionDetail, Long> {

	Optional<ExhibitionDetail> findByExhibitionId(Long exhibitionId);

	boolean existsByExhibitionId(Long exhibitionId);

	List<ExhibitionDetail> findByExhibitionIdIn(Collection<Long> exhibitionIds);

	List<ExhibitionDetail> findByDescriptionIsNotNull();

}
