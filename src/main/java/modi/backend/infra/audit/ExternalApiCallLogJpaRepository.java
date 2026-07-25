package modi.backend.infra.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import modi.backend.domain.audit.ExternalApiCallLog;

public interface ExternalApiCallLogJpaRepository extends JpaRepository<ExternalApiCallLog, Long> {
}
