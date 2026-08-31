package modi.backend.ingestionv2.stage.domain;

import java.util.Optional;

/**
 * 스테이징 애그리거트 포트.
 *
 * <ul>
 *   <li>조회 키는 항상 문화포털 원천 키(도메인 사이 상관 키)</li>
 *   <li>중복 전달 직렬화를 위한 잠금 조회를 별도 메서드로 노출</li>
 * </ul>
 */
public interface StageRepository {

	Optional<Staging> findByVendorKey(String vendorKey);

	/** 반영 트랜잭션 진입용 잠금 조회. 같은 전시에 대한 동시 처리를 직렬화한다. */
	Optional<Staging> findByVendorKeyForUpdate(String vendorKey);

	Staging save(Staging staging);
}
