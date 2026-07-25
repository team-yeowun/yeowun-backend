package modi.backend.ingestion.infra.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import modi.backend.ingestion.domain.audit.IngestionRun;

public interface IngestionRunJpaRepository extends JpaRepository<IngestionRun, Long> {
}
