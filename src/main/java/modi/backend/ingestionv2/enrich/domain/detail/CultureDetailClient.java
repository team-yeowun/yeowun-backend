package modi.backend.ingestionv2.enrich.domain.detail;

/**
 * 문화포털 상세 조회 포트.
 *
 * <ul>
 *   <li>원천에 상세가 없으면 예외가 아니라 absent 값. "없음을 확인함"도 조회 완료</li>
 *   <li>전송 실패와 벤더 실패만 예외. 재시도 판정은 도메인 몫</li>
 * </ul>
 */
public interface CultureDetailClient {

	DetailData fetchDetail(String vendorKey);
}
