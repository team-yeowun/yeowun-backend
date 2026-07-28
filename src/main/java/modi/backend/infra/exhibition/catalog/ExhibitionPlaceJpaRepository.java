package modi.backend.infra.exhibition.catalog;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import modi.backend.domain.exhibition.catalog.ExhibitionPlace;

/** Spring Data JPA — 전시장. */
public interface ExhibitionPlaceJpaRepository extends JpaRepository<ExhibitionPlace, Long> {

	Optional<ExhibitionPlace> findByPlaceKeyAndDeletedAtIsNull(String placeKey);

	/** 단건 조회(살아있는 행만). */
	Optional<ExhibitionPlace> findByIdAndDeletedAtIsNull(Long id);

	/** 목록 조립용 배치 조회(살아있는 행만) — 삭제된 행을 실어와 앱에서 버리지 않는다. */
	List<ExhibitionPlace> findByIdInAndDeletedAtIsNull(Collection<Long> ids);
}
