package modi.backend.ingestionv2.inspect.domain;

/**
 * 점검이 보는 원장 단면 (점검이 소유하는 읽기 전용 값).
 *
 * <ul>
 *   <li>원장은 첫 관측을 기록하고 덮어쓰지 않으므로 이 단면은 읽는 동안 변하지 않음</li>
 *   <li>값은 전부 벤더 원문 그대로 (타입 복원은 검증 규칙과 어셈블의 몫)</li>
 *   <li>개장 시간 JSON은 파싱하지 않고 존재 여부만 봄 (해석은 어셈블의 몫)</li>
 * </ul>
 */
public record InspectionLedger(
		String title,
		String startDate,
		String endDate,
		String area,
		String gpsX,
		String gpsY,
		String genreKeyword,
		boolean placeAbsent,
		String openingHours) {
}
