package modi.backend.ingestion.infra.snapshot;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import modi.backend.ingestion.domain.snapshot.GooglePlaceSnapshot;

public interface GooglePlaceSnapshotJpaRepository extends JpaRepository<GooglePlaceSnapshot, Long> {

	Optional<GooglePlaceSnapshot> findByExhibitionPlaceId(Long exhibitionPlaceId);
}
