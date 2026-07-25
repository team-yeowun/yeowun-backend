package modi.backend.ingestion.infra.culture;

import java.util.List;

import modi.backend.domain.exhibition.catalog.CatalogDetailData;
import modi.backend.domain.exhibition.catalog.ExhibitionCategory;
import modi.backend.domain.exhibition.catalog.ExhibitionRegion;
import modi.backend.ingestion.domain.data.CatalogExhibitionData;
import modi.backend.ingestion.domain.data.CultureDetailPayload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import tools.jackson.dataformat.xml.annotation.JacksonXmlProperty;

/**
 * 한눈에보는문화정보(15138937) XML 응답 바인딩 — 외곽 1클래스에 목록·상세 응답을 중첩 record로 묶는다(컨벤션 · 파일 수 절감).
 * <p>
 * <b>목록({@link Realm2ListResponse})과 상세({@link Detail2Response})는 여전히 타입을 나눠 선언한다</b> — 한 파일에
 * 모았을 뿐 <b>같은 타입이 아니다</b>. 두 응답은 겹치는 필드가 10개 있지만 우연히 같을 뿐 같은 계약이 아니라서,
 * 하나로 겸하면 목록 응답에 상세 전용 필드가 {@code null}로 딸려 나오고, 원천이 한쪽 응답만 바꾸는 날 공통 타입은
 * 양쪽을 함께 깨뜨린다. 중첩은 co-location일 뿐 타입 통합이 아니다.
 * <p>
 * <b>필드는 실측으로 확정</b>했다 — realm2는 12태그, detail2는 18태그를 내려준다(2026-07-21 전수 집계).
 *
 * <p><b>중첩 {@code Items} 우회는 반드시 유지</b>: {@code <items>} 하위 {@code <item>} 반복 구조를 record 필드에
 * {@code @JacksonXmlElementWrapper}로 직접 붙이면 레코드 암묵 생성자의 파라미터명 해석과 충돌해
 * {@code InvalidDefinitionException}이 난다(Jackson 2.21.4·3.1.4 모두 재현 — 애노테이션 {@code @Target}에 PARAMETER가
 * 있어 컴포넌트 rename이 생성자 프로퍼티명까지 바꾼다). {@code Items} 레코드로 감싸는 우회를 평탄화하면 런타임에 터진다.
 */
public final class KoreaCultureDto {

	private KoreaCultureDto() {
	}

	/**
	 * <b>realm2(목록)</b> 응답. realm2가 실제로 내려주는 12태그만 매핑한다 —
	 * {@code price}·{@code placeAddr} 같은 상세 전용 필드는 이 응답에 <b>존재하지 않는다</b>.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Realm2ListResponse(Header header, Body body) {

		@JsonIgnoreProperties(ignoreUnknown = true)
		public record Header(String resultCode, String resultMsg) {}

		@JsonIgnoreProperties(ignoreUnknown = true)
		public record Body(Integer totalCount, Items items) {}

		@JsonIgnoreProperties(ignoreUnknown = true)
		public record Items(
				@JacksonXmlElementWrapper(useWrapping = false)
				@JacksonXmlProperty(localName = "item") List<Item> item) {}

		/** realm2가 실제로 내려주는 12태그 — 전수 집계로 확정(상세 전용 필드는 없다). */
		@JsonIgnoreProperties(ignoreUnknown = true)
		public record Item(String seq, String title, String startDate, String endDate, String place,
				String realmName, String area, String sigungu, String thumbnail, String gpsX, String gpsY,
				String serviceName) {

			/**
			 * 이 응답 아이템을 도메인 수집 데이터로 옮긴다 — 변환 규칙은 {@link CultureFieldCodec}가 목록·상세와 공유한다.
			 * <p>
			 * <b>도메인 쪽에 팩토리를 두지 않는 이유</b>: {@link CatalogExhibitionData}가 이 record(Jackson 바인딩)와
			 * Spring 디코더를 알게 되기 때문이다. 응답이 자기를 도메인 값으로 표현하는 방향이라야 의존이 안쪽으로만 흐른다.
			 */
			public CatalogExhibitionData toCatalog() {
				return new CatalogExhibitionData(
						seq,
						CultureFieldCodec.decode(title),
						CultureFieldCodec.decode(CultureFieldCodec.blankToNull(place)),
						CultureFieldCodec.parseDate(startDate),
						CultureFieldCodec.parseDate(endDate),
						ExhibitionRegion.fromAreaText(area),
						ExhibitionCategory.fromRealmName(realmName),
						CultureFieldCodec.blankToNull(thumbnail),
						null, // detailUrl — 목록 응답에는 없다(상세 조회가 채운다)
						CultureFieldCodec.blankToNull(serviceName),
						CultureFieldCodec.parseCoordinate(gpsX),
						CultureFieldCodec.parseCoordinate(gpsY),
						CultureFieldCodec.blankToNull(sigungu),
						CultureFieldCodec.decode(CultureFieldCodec.blankToNull(realmName)),
						CultureFieldCodec.decode(CultureFieldCodec.blankToNull(area)));
			}
		}

		public boolean isSuccess() {
			return header != null && CultureResultCode.isSuccess(header.resultCode());
		}

		/** 원천 표준 결과코드 — 없으면 null(응답 자체가 비정상). */
		public String resultCode() {
			return header == null ? null : header.resultCode();
		}

		/** 원천이 말한 총 건수 — 응답에 없으면 null("모른다", 0이 아니다). */
		public Integer totalCount() {
			return body == null ? null : body.totalCount();
		}

		public List<Item> items() {
			return body == null || body.items() == null || body.items().item() == null
					? List.of()
					: body.items().item();
		}
	}

	/**
	 * <b>detail2(상세)</b> 응답. detail2가 내려주는 18태그만 매핑한다 —
	 * 목록에만 있는 {@code thumbnail}·{@code serviceName}은 이 응답에 <b>존재하지 않는다</b>(이미지는 {@code imgUrl}).
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Detail2Response(Header header, Body body) {

		@JsonIgnoreProperties(ignoreUnknown = true)
		public record Header(String resultCode, String resultMsg) {}

		@JsonIgnoreProperties(ignoreUnknown = true)
		public record Body(Integer totalCount, Items items) {}

		@JsonIgnoreProperties(ignoreUnknown = true)
		public record Items(
				@JacksonXmlElementWrapper(useWrapping = false)
				@JacksonXmlProperty(localName = "item") List<Item> item) {}

		/** detail2가 실제로 내려주는 18태그 — 전수 집계로 확정(목록 전용 thumbnail·serviceName은 없다). */
		@JsonIgnoreProperties(ignoreUnknown = true)
		public record Item(String seq, String title, String startDate, String endDate, String place,
				String realmName, String area, String sigungu, String gpsX, String gpsY,
				String price, String contents1, String url, String phone, String imgUrl,
				String placeUrl, String placeAddr, String placeSeq) implements CultureDetailPayload {

			/**
			 * 이 응답 아이템을 도메인 상세 값으로 옮긴다 — 변환 규칙은 {@link CultureFieldCodec}가 목록과 공유한다.
			 * <p>
			 * {@code contents1}은 여기서 <b>평문</b>이 된다. 변환 전 원문은 {@link CultureDetailSnapshot}이
			 * 응답 그대로 적재하므로, 평문 추출 규칙이 바뀌면 거기서 다시 뽑을 수 있다.
			 */
			@Override
			public CatalogDetailData toDetail() {
				return new CatalogDetailData(
						CultureFieldCodec.decode(CultureFieldCodec.blankToNull(price)),
						CultureFieldCodec.decodeDescription(CultureFieldCodec.blankToNull(contents1)),
						CultureFieldCodec.blankToNull(url),
						CultureFieldCodec.decode(CultureFieldCodec.blankToNull(phone)),
						CultureFieldCodec.blankToNull(imgUrl),
						CultureFieldCodec.blankToNull(placeUrl),
						CultureFieldCodec.decode(CultureFieldCodec.blankToNull(placeAddr)),
						CultureFieldCodec.blankToNull(placeSeq));
			}
		}

		public boolean isSuccess() {
			return header != null && CultureResultCode.isSuccess(header.resultCode());
		}

		/** 원천 표준 결과코드 — 없으면 null(응답 자체가 비정상). */
		public String resultCode() {
			return header == null ? null : header.resultCode();
		}

		public List<Item> items() {
			return body == null || body.items() == null || body.items().item() == null
					? List.of()
					: body.items().item();
		}
	}
}
