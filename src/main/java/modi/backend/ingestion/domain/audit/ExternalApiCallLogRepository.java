package modi.backend.ingestion.domain.audit;

import modi.backend.ingestion.domain.audit.ExternalApiCallLog;

/**
 * 외부 호출 감사 저장 포트(Spring 무의존). append-only라 저장만 제공한다.
 */
public interface ExternalApiCallLogRepository {

	ExternalApiCallLog save(ExternalApiCallLog externalApiCall);
}
