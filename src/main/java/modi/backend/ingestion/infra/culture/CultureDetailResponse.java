package modi.backend.ingestion.infra.culture;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import tools.jackson.dataformat.xml.annotation.JacksonXmlProperty;

/**
 * 한눈에보는문화정보(15138937) <b>detail2(상세)</b> XML 응답 매핑.
 * <p>
 * 목록({@link CultureRealmListResponse})과 <b>타입을 나눠 선언한다</b> — 겹치는 필드가 10개 있지만 우연히 같을 뿐
 * 같은 계약이 아니다. 원천이 한쪽 응답만 바꾸는 날 공통 타입은 양쪽을 함께 깨뜨린다.
 * <p>
 * <b>필드는 실측으로 확정</b>했다 — detail2는 아래 18태그를 내려준다(2026-07-21 표본 확인 · 기존 60건 전수 집계와 일치).
 * 목록에만 있는 {@code thumbnail}·{@code serviceName}은 이 응답에 <b>존재하지 않는다</b>(이미지는 {@code imgUrl}).
 *
 * <p><b>중첩 {@code Items} 우회</b>: 목록 응답과 같은 이유로 반드시 유지해야 한다 —
 * record 컴포넌트에 {@code @JacksonXmlElementWrapper}를 직접 붙이면 {@code InvalidDefinitionException}이 난다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CultureDetailResponse(Header header, Body body) {

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
			String placeUrl, String placeAddr, String placeSeq) {}

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
