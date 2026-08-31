package modi.backend.ingestionv2.collect.domain;

/**
 * 수집 격벽의 저장 포트.
 *
 * <ul>
 *   <li>회차 마크 선점 · 멱등 판정 · 애그리거트 저장 · 원장 적재를 한 포트에 모음</li>
 *   <li>Spring 무의존 (구현은 infra/CollectRepositoryImpl)</li>
 * </ul>
 */
public interface CollectRepository {

	/** 이 회차를 선점한다. 이미 선점되어 있으면 false. */
	boolean claimBatchMark(CollectBatchMark mark);

	/** 이미 확정된 전시인지 판정한다. */
	boolean existsByVendorKey(String vendorKey);

	void save(CollectedExhibition collected);

	void saveSnapshot(CultureListSnapshot snapshot);
}
