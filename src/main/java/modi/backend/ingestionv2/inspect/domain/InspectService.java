package modi.backend.ingestionv2.inspect.domain;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import modi.backend.ingestionv2.common.event.IngestionEventType;
import modi.backend.ingestionv2.common.outbox.OutboxAppender;
import modi.backend.support.error.CoreException;

/**
 * 점검 유스케이스.
 *
 * <ul>
 *   <li>이미 점검된 전시는 조기 반환 (재전달 중복 흡수)</li>
 *   <li>판단은 규칙이, 상태 결정은 애그리거트가, 서비스는 조회와 저장만</li>
 *   <li>통과한 경우에만 INSPECTED 적재 (반려는 커밋되지만 진행하지 않음)</li>
 *   <li>원장 결손은 반려가 아니라 예외 (선행 격벽의 불변식 위반)</li>
 *   <li>트랜잭션 경계는 파사드가 소유. 이 클래스는 그 경계 안에서만 호출된다</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class InspectService {

	private final InspectionRepository inspectionRepository;
	private final InspectionLedgerRepository inspectionLedgerRepository;
	private final OutboxAppender outboxAppender;

	public void inspect(String vendorKey) {
		if (inspectionRepository.findByVendorKey(vendorKey).isPresent()) {
			return;
		}
		Inspection inspection = Inspection.create(vendorKey, evaluate(vendorKey));
		inspectionRepository.save(inspection);
		appendIfPassed(inspection);
	}

	public void reinspect(String vendorKey) {
		Inspection inspection = inspectionRepository.findByVendorKey(vendorKey)
				.orElseThrow(() -> new CoreException(InspectErrorCode.INSPECTION_NOT_FOUND));
		inspection.reevaluate(evaluate(vendorKey));
		// 더티 체킹만으로도 반영되지만 저장 지점이 코드에 보이도록 명시한다.
		inspectionRepository.save(inspection);
		appendIfPassed(inspection);
	}

	private InspectionVerdict evaluate(String vendorKey) {
		InspectionLedger ledger = inspectionLedgerRepository.findByVendorKey(vendorKey)
				.orElseThrow(() -> new CoreException(InspectErrorCode.LEDGER_MISSING));
		return InspectionRule.evaluate(ledger);
	}

	private void appendIfPassed(Inspection inspection) {
		if (inspection.isPassed()) {
			outboxAppender.append(IngestionEventType.INSPECTED, inspection.getVendorKey());
		}
	}
}
