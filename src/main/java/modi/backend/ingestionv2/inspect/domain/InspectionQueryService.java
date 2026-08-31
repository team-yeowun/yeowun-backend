package modi.backend.ingestionv2.inspect.domain;

import java.util.List;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import modi.backend.support.error.CoreException;

/**
 * 점검 조회 유스케이스.
 *
 * <ul>
 *   <li>명령 서비스와 분리 (조회는 읽기 전용 경계 안에서 돌고 이벤트를 적재하지 않음)</li>
 *   <li>트랜잭션 어노테이션은 갖지 않음 (경계는 파사드가 소유. 이 클래스는 경계 안에서만 호출됨)</li>
 *   <li>단건 조회는 원장 단면을 함께 반환 (반려 원인을 화면에서 바로 확인하기 위함)</li>
 *   <li>원장이 없으면 예외 대신 빈 값 (결손 진단이 이 화면의 용도)</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class InspectionQueryService {

	private final InspectionRepository inspectionRepository;
	private final InspectionQueryRepository inspectionQueryRepository;
	private final InspectionLedgerRepository inspectionLedgerRepository;

	public InspectResult.RejectedPage findRejected(InspectCriteria.RejectedSearch criteria) {
		List<InspectResult.Summary> items = inspectionQueryRepository
				.findRejected(criteria.reason(), criteria.offset(), criteria.size())
				.stream()
				.map(InspectResult.Summary::from)
				.toList();
		long totalCount = inspectionQueryRepository.countRejected(criteria.reason());
		return new InspectResult.RejectedPage(items, criteria.page(), criteria.size(), totalCount);
	}

	public InspectResult.Detail findDetail(String vendorKey) {
		Inspection inspection = inspectionRepository.findByVendorKey(vendorKey)
				.orElseThrow(() -> new CoreException(InspectErrorCode.INSPECTION_NOT_FOUND));
		InspectResult.LedgerView ledger = inspectionLedgerRepository.findByVendorKey(vendorKey)
				.map(InspectResult.LedgerView::from)
				.orElse(null);
		return new InspectResult.Detail(InspectResult.Summary.from(inspection), ledger);
	}
}
