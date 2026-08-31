package modi.backend.ingestionv2.enrich.interfaces;

import org.springframework.stereotype.Component;

import modi.backend.ingestionv2.common.event.IngestionEventType;
import modi.backend.ingestionv2.enrich.domain.EnrichFacade;
import modi.backend.ingestionv2.enrich.domain.EnrichStep;
import modi.backend.ingestionv2.enrich.domain.hours.EnrichmentHours;

/** 개장 시간 보강 실행 핸들러. Google Places를 부르는 스텝이라 ingestion.google 스트림에서 온다. */
@Component
class HoursReadyEventHandler extends EnrichEventHandler {

	HoursReadyEventHandler(EnrichFacade enrichFacade) {
		super(enrichFacade);
	}

	@Override
	protected IngestionEventType eventType() {
		return IngestionEventType.HOURS_READY;
	}

	@Override
	protected EnrichStep step() {
		return EnrichStep.HOURS;
	}

	@Override
	protected void execute(String vendorKey) {
		enrichFacade.enrichHours(vendorKey);
	}

	@Override
	protected String vendorName() {
		return EnrichmentHours.VENDOR;
	}
}
