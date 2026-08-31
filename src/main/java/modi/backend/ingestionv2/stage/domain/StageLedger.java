package modi.backend.ingestionv2.stage.domain;

/**
 * 원장 읽기 결과. 스테이징이 소유하는 어휘이며 앞 도메인의 엔티티가 아니다.
 *
 * <ul>
 *   <li>원장은 벤더 응답을 문자열 그대로 적재하므로 여기서도 문자열</li>
 *   <li>타입 복원은 어셈블러의 몫이고 이 레코드는 옮겨 담기만 담당</li>
 *   <li>목록 이름을 Listing 으로 둔 이유는 java.util.List 와의 혼동 회피</li>
 * </ul>
 */
public final class StageLedger {

	private StageLedger() {
	}

	/** 문화포털 목록 원장. */
	public record Listing(
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
	}

	/** 문화포털 상세 원장. absent 는 벤더가 상세를 갖고 있지 않다는 정상 관측이다. */
	public record Detail(
			boolean absent,
			String price,
			String contents,
			String url,
			String phone,
			String imgUrl,
			String place) {
	}

	/**
	 * 장르 원장. 정제 결과인 키워드와 그것을 뽑은 공급자와 모델을 함께 갖는다.
	 *
	 * <p>원장 쪽 열 이름은 vendor 이고 여기서 provider 인 이유는, 이 값이 최종적으로 코어의
	 * GenreProvider 열거형이 되기 때문이다. 어휘는 받는 쪽인 코어가 소유한다.
	 */
	public record Genre(String keyword, String provider, String model) {
	}

	/** 구글 장소 원장. 영업시간은 깊은 중첩이라 구조 보존 JSON 문자열로 남아 있다. */
	public record Place(boolean absent, String regularOpeningHours) {
	}
}
