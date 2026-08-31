package modi.backend.ingestionv2.enrich.interfaces;

import org.springframework.stereotype.Component;

import modi.backend.ingestionv2.common.event.IngestionEventType;
import modi.backend.ingestionv2.enrich.domain.EnrichFacade;
import modi.backend.ingestionv2.enrich.domain.EnrichStep;

/**
 * 수집 완료를 받아 보강을 여는 핸들러.
 *
 * <ul>
 *   <li>담당 스텝이 없음. 시도 횟수를 적을 하위 엔티티가 아직 없어 실패를 셀 자리가 없음</li>
 *   <li>그러므로 실패는 곧바로 상한 소진으로 취급. 공통부가 RETRY_EXHAUSTED로 바꿔 던짐</li>
 *   <li>격벽 사이의 유일한 수신구. COLLECTED가 들어오는 자리는 이 클래스 하나</li>
 * </ul>
 */
@Component
class CollectedEventHandler extends EnrichEventHandler {

	CollectedEventHandler(EnrichFacade enrichFacade) {
		super(enrichFacade);
	}

	@Override
	protected IngestionEventType eventType() {
		return IngestionEventType.COLLECTED;
	}

	@Override
	protected EnrichStep step() {
		return null;
	}

	@Override
	protected void execute(String vendorKey) {
		enrichFacade.startEnrichment(vendorKey);
	}

	@Override
	protected String vendorName() {
		return null;
	}
}
