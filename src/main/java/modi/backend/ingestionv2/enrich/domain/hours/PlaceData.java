package modi.backend.ingestionv2.enrich.domain.hours;

/** 구글 Places 응답 1건. 영업시간은 깊은 중첩이라 JSON 문자열로 구조를 보존한다. */
public record PlaceData(String placeId, String displayName, String formattedAddress, String regularOpeningHours,
		boolean absent) {

	/** 구글이 장소를 찾지 못했을 때의 값. */
	public static PlaceData notFound() {
		return new PlaceData(null, null, null, null, true);
	}
}
