package modi.backend.ingestionv2.inspect.domain;

/**
 * 통과시키되 남겨두는 관찰.
 *
 * <ul>
 *   <li>진행을 멈추지 않음 (없어도 전시가 서비스에서 의미를 잃지 않는 값)</li>
 *   <li>코어 enum을 들이지 않고 자기 어휘로 기록 (점검은 코어 계약을 호출하지 않는 격벽)</li>
 * </ul>
 */
public enum InspectionNote {

	/** area 텍스트가 코어 지역 매핑에 걸리지 않아 ETC로 떨어짐. */
	REGION_UNMAPPED,

	/** gpsX 또는 gpsY가 실수로 파싱되지 않음 (지도 표시에서만 빠짐). */
	COORDINATE_UNPARSABLE,

	/** 구글이 전시장을 찾지 못함. */
	HOURS_PLACE_NOT_FOUND,

	/** 전시장은 찾았으나 개장 시간 값이 없음. */
	HOURS_EMPTY
}
