package modi.backend.ingestionv2.inspect.interfaces;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import modi.backend.ingestionv2.common.IngestionErrorCode;
import modi.backend.ingestionv2.common.event.IngestionEventType;
import modi.backend.ingestionv2.common.queue.IngestionEventHandler;
import modi.backend.ingestionv2.inspect.domain.InspectErrorCode;
import modi.backend.ingestionv2.inspect.domain.InspectFacade;
import modi.backend.support.error.CoreException;

/**
 * 점검 격벽의 이벤트 수신구.
 *
 * <ul>
 *   <li>공용 포트 구현 (배선 방향이 격벽에서 공용으로 향해 공용이 격벽을 모름)</li>
 *   <li>ENRICHED 하나만 수신 (점검을 깨우는 사실은 하나뿐)</li>
 *   <li>파사드만 호출 (서비스 직접 호출 금지)</li>
 *   <li>결손만 배달 계층의 어휘로 번역해 다시 던짐 (격리 요청)</li>
 *   <li>그 밖의 예외는 손대지 않고 통과 (미확인으로 남아 다시 시도됨)</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class InspectEventHandler implements IngestionEventHandler {

	private final InspectFacade inspectFacade;

	@Override
	public boolean supports(IngestionEventType type) {
		return type == IngestionEventType.ENRICHED;
	}

	@Override
	public void handle(String vendorKey) {
		try {
			inspectFacade.inspect(vendorKey);
		} catch (CoreException failure) {
			if (failure.errorCode() == InspectErrorCode.LEDGER_MISSING) {
				// 원장 결손은 벤더 데이터가 아니라 선행 격벽의 불변식이 깨졌다는 신호다. 재시도로 풀리지 않는다.
				throw new CoreException(IngestionErrorCode.RETRY_EXHAUSTED, failure.getMessage());
			}
			throw failure;
		}
	}
}
