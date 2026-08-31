package modi.backend.ingestionv2.enrich.interfaces;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import modi.backend.ingestionv2.common.event.IngestionEventType;
import modi.backend.ingestionv2.enrich.domain.EnrichFacade;
import modi.backend.ingestionv2.enrich.domain.EnrichStep;
import modi.backend.ingestionv2.enrich.domain.detail.EnrichmentDetail;

/** 상세 보강 실행 핸들러. 문화포털을 부르는 스텝이라 ingestion.culture 스트림에서 온다. */
@Component
@ConditionalOnProperty(prefix = "app.ingestion.v2", name = "consume-handler",
		havingValue = "REAL", matchIfMissing = true)
class DetailReadyEventHandler extends EnrichEventHandler {

	DetailReadyEventHandler(EnrichFacade enrichFacade) {
		super(enrichFacade);
	}

	@Override
	protected IngestionEventType eventType() {
		return IngestionEventType.DETAIL_READY;
	}

	@Override
	protected EnrichStep step() {
		return EnrichStep.DETAIL;
	}

	@Override
	protected void execute(String vendorKey) {
		enrichFacade.enrichDetail(vendorKey);
	}

	@Override
	protected String vendorName() {
		return EnrichmentDetail.VENDOR;
	}
}
