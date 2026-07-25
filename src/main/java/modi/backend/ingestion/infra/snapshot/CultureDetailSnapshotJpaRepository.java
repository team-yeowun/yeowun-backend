package modi.backend.ingestion.infra.snapshot;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import modi.backend.ingestion.domain.snapshot.CultureDetailSnapshot;

public interface CultureDetailSnapshotJpaRepository extends JpaRepository<CultureDetailSnapshot, Long> {

	Optional<CultureDetailSnapshot> findByExternalId(String externalId);
}
