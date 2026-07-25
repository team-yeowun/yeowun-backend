package modi.backend.infra.exhibition.catalog;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import modi.backend.domain.exhibition.catalog.ExhibitionPlace;

/** Spring Data JPA — 전시장. */
public interface ExhibitionPlaceJpaRepository extends JpaRepository<ExhibitionPlace, Long> {

	Optional<ExhibitionPlace> findByPlaceKeyAndDeletedAtIsNull(String placeKey);
}
