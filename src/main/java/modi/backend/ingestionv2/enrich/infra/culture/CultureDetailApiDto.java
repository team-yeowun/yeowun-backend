package modi.backend.ingestionv2.enrich.infra.culture;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import modi.backend.ingestionv2.enrich.domain.detail.DetailData;
import tools.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import tools.jackson.dataformat.xml.annotation.JacksonXmlProperty;

/**
 * 문화포털 상세(detail2) XML 응답 바인딩.
 *
 * <ul>
 *   <li>응답 record가 자기를 보강 격벽 어휘로 옮김 - HTML 엔티티가 섞인 설명도 원문 그대로</li>
 *   <li>설명 원문은 contents1 태그 - 원장 컬럼명(contents)과 이름만 다름</li>
 *   <li>벤더 실패는 HTTP 200 + 헤더 결과 코드로 도착하므로 성공 판정을 응답이 소유</li>
 * </ul>
 */
public final class CultureDetailApiDto {

	/** 공공데이터포털 표준 결과 코드 중 정상. */
	static final String NORMAL_SERVICE = "00";

	private CultureDetailApiDto() {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record DetailResponse(Header header, Body body) {

		@JsonIgnoreProperties(ignoreUnknown = true)
		public record Header(String resultCode, String resultMsg) {
		}

		@JsonIgnoreProperties(ignoreUnknown = true)
		public record Body(Integer totalCount, Items items) {
		}

		@JsonIgnoreProperties(ignoreUnknown = true)
		public record Items(
				@JacksonXmlElementWrapper(useWrapping = false)
				@JacksonXmlProperty(localName = "item") List<Item> item) {
		}

		@JsonIgnoreProperties(ignoreUnknown = true)
		public record Item(String seq, String title, String startDate, String endDate, String place, String realmName,
				String area, String sigungu, String gpsX, String gpsY, String price, String contents1, String url,
				String phone, String imgUrl) {

			/** 보강 격벽 어휘로 옮김. 평문 추출·타입 변환 없음. */
			public DetailData toDetailData() {
				return new DetailData(title, startDate, endDate, place, realmName, area, sigungu, gpsX, gpsY,
						price, contents1, url, phone, imgUrl, false);
			}
		}

		public boolean isSuccess() {
			return header != null && NORMAL_SERVICE.equals(trim(header.resultCode()));
		}

		public String vendorFailure() {
			return header == null ? "응답 헤더 없음" : trim(header.resultCode()) + " " + trim(header.resultMsg());
		}

		public List<Item> items() {
			return body == null || body.items() == null || body.items().item() == null
					? List.of()
					: body.items().item();
		}

		private static String trim(String value) {
			return value == null ? "" : value.trim();
		}
	}
}
