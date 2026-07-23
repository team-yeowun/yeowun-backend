package modi.backend.ingestion.domain.audit;

import modi.backend.ingestion.domain.audit.IngestionRun;

/** 동기화 실행 기록 저장 포트(Spring 무의존). append-only라 저장만 제공한다. */
public interface IngestionRunRepository {

	IngestionRun save(IngestionRun syncRun);
}
