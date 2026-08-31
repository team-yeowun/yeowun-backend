package modi.backend.ingestionv2.inspect.domain;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * 점검 격벽의 유일한 진입점.
 *
 * <ul>
 *   <li>트랜잭션 경계 소유 (유스케이스 하나 = 트랜잭션 하나)</li>
 *   <li>명령 서비스와 조회 서비스를 조율 (서비스끼리는 서로를 호출하지 않음)</li>
 *   <li>조회는 읽기 전용 경계 (엔티티 변경이 섞이지 않게 함)</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class InspectFacade {

	private final InspectService inspectService;
	private final InspectionQueryService inspectionQueryService;

	/** ENRICHED 소비 경로. 이미 점검된 전시면 아무 일도 하지 않는다. */
	@Transactional
	public void inspect(String vendorKey) {
		inspectService.inspect(vendorKey);
	}

	/** 관리자 재검사 경로. 반려된 전시만 다시 판정한다. */
	@Transactional
	public void reinspect(String vendorKey) {
		inspectService.reinspect(vendorKey);
	}

	/** 관리자 반려 목록. 사유가 null이면 전체 반려. 읽기 전용 경계는 이 자리가 소유한다. */
	@Transactional(readOnly = true)
	public InspectResult.RejectedPage findRejected(InspectCriteria.RejectedSearch criteria) {
		return inspectionQueryService.findRejected(criteria);
	}

	/** 관리자 단건 진단. 점검 결과와 원장 단면을 함께 돌려준다. */
	@Transactional(readOnly = true)
	public InspectResult.Detail findDetail(String vendorKey) {
		return inspectionQueryService.findDetail(vendorKey);
	}
}
