package modi.backend.ingestionv2.enrich.domain.hours;

/**
 * 구글 Places 조회 포트.
 *
 * <ul>
 *   <li>장소를 찾지 못하면 예외가 아니라 absent 값. 조회했다는 사실이 완비의 근거</li>
 * </ul>
 */
public interface PlaceHoursClient {

	PlaceData fetchPlace(String placeName, String placeAddress);
}
