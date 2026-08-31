package modi.backend.ingestionv2.enrich.domain.detail;

/** 문화포털 상세 응답 1건. 정제하지 않은 원문 문자열을 그대로 담는다. */
public record DetailData(String title, String startDate, String endDate, String place, String realmName, String area,
		String sigungu, String gpsX, String gpsY, String price, String contents, String url, String phone,
		String imgUrl, boolean absent) {

	/** 원천에 상세가 없을 때의 값. 조회했다는 사실만 남긴다. */
	public static DetailData none() {
		return new DetailData(null, null, null, null, null, null, null, null, null, null, null, null, null, null, true);
	}
}
