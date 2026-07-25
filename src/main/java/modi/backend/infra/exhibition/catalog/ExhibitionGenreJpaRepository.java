package modi.backend.infra.exhibition.catalog;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import modi.backend.domain.exhibition.catalog.ExhibitionGenre;

public interface ExhibitionGenreJpaRepository extends JpaRepository<ExhibitionGenre, Long> {

	Optional<ExhibitionGenre> findByExhibitionId(Long exhibitionId);
}
