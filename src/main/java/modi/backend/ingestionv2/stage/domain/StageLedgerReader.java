package modi.backend.ingestionv2.stage.domain;

import java.util.Optional;

/**
 * 원장 읽기 포트.
 *
 * <ul>
 *   <li>조회 전용이라 쓰기 메서드가 없음</li>
 *   <li>상관 키는 문화포털 원천 키 하나</li>
 *   <li>없을 수 있는 원장(상세·구글)과 반드시 있어야 하는 원장(목록·장르)의 구분은 어셈블러가 판단</li>
 * </ul>
 */
public interface StageLedgerReader {

	Optional<StageLedger.Listing> readListing(String vendorKey);

	Optional<StageLedger.Detail> readDetail(String vendorKey);

	Optional<StageLedger.Genre> readGenre(String vendorKey);

	Optional<StageLedger.Place> readPlace(String vendorKey);
}
