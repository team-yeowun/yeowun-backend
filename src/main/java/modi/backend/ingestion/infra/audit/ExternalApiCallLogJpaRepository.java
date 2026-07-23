package modi.backend.ingestion.infra.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import modi.backend.ingestion.domain.audit.ExternalApiCallLog;

public interface ExternalApiCallLogJpaRepository extends JpaRepository<ExternalApiCallLog, Long> {
}
