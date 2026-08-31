package modi.backend.ingestionv2.inspect.domain;

import java.util.Optional;

/**
 * 원장 단면 조회 포트.
 *
 * <ul>
 *   <li>다른 격벽이 쓴 테이블을 읽되 그 격벽의 클래스는 알지 못함</li>
 *   <li>세 원장 중 하나라도 행이 없으면 빈 값 (결손은 반려가 아니라 예외로 다룸)</li>
 * </ul>
 */
public interface InspectionLedgerRepository {

	Optional<InspectionLedger> findByVendorKey(String vendorKey);
}
