package modi.backend.ingestionv2.collect.domain;

import modi.backend.support.error.CoreException;

/**
 * 목록 응답의 전시 1건.
 *
 * <ul>
 *   <li>어댑터의 반환 타입이자 원장 적재 입력 (수집 격벽 안에서 어휘가 하나로 모임)</li>
 *   <li>vendorKey 유효성은 여기서 한 번만 검사 (엔티티에서 반복하지 않음)</li>
 *   <li>나머지 필드는 verbatim (원천이 비워 보낸 값은 비운 채로 통과)</li>
 * </ul>
 */
public record CatalogItem(
		String vendorKey,
		String title,
		String startDate,
		String endDate,
		String place,
		String realmName,
		String area,
		String sigungu,
		String thumbnail,
		String gpsX,
		String gpsY,
		String serviceName,
		String detailUrl) {

	public CatalogItem {
		if (vendorKey == null || vendorKey.isBlank()) {
			throw new CoreException(CollectErrorCode.INVALID_VENDOR_KEY);
		}
	}
}
