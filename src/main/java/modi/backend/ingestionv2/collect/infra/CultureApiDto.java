package modi.backend.ingestionv2.collect.infra;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import modi.backend.ingestionv2.collect.domain.CatalogItem;
import tools.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import tools.jackson.dataformat.xml.annotation.JacksonXmlProperty;

/**
 * 문화포털 목록(realm2) XML 응답 바인딩.
 *
 * <ul>
 *   <li>응답 record가 자기를 수집 격벽 어휘로 옮김 (날짜·좌표도 문자열 그대로)</li>
 *   <li>벤더 실패는 HTTP 200 + 헤더 결과 코드로 도착하므로 성공 판정을 응답이 소유</li>
 *   <li>중첩 Items 우회 유지 (record 필드에 래퍼 어노테이션을 직접 붙이면 생성자 해석과 충돌)</li>
 * </ul>
 */
public final class CultureApiDto {

	/** 공공데이터포털 표준 결과 코드 중 정상. */
	static final String NORMAL_SERVICE = "00";

	private CultureApiDto() {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ListResponse(Header header, Body body) {

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
		public record Item(
				String seq,
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
				String url) {

			/** 수집 격벽 어휘로 옮김. 디코딩·타입 변환 없음. */
			public CatalogItem toCatalogItem() {
				return new CatalogItem(
						seq, title, startDate, endDate, place, realmName, area, sigungu,
						thumbnail, gpsX, gpsY, serviceName, url);
			}
		}

		/** 정상 응답인가. 본문이 통째로 없는 실패도 있어 헤더 코드만으로 판정한다. */
		public boolean isSuccess() {
			return header != null && NORMAL_SERVICE.equals(trim(header.resultCode()));
		}

		/** 실패 사유 라벨. 원천이 준 코드와 메시지를 그대로 실어 운영 로그에서 읽는다. */
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
