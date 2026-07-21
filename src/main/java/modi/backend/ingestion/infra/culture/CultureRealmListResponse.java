package modi.backend.ingestion.infra.culture;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import tools.jackson.dataformat.xml.annotation.JacksonXmlProperty;

/**
 * 한눈에보는문화정보(15138937) <b>realm2(목록)</b> XML 응답 매핑.
 * <p>
 * 상세(detail2)와 <b>타입을 나눠 선언한다</b> — 두 응답은 겹치는 필드가 있을 뿐 같은 계약이 아니다. 하나로 겸하면
 * 목록 응답에 상세 전용 필드가 {@code null}로 딸려 나와 "이게 상세인가?"를 매번 되묻게 되고, 목록에서 절대
 * 채워지지 않는 값을 읽는 코드가 컴파일을 통과한다.
 * <p>
 * <b>필드는 실측으로 확정</b>했다 — realm2는 아래 12태그만 내려준다(2026-07-21 · 100건 전수 집계).
 * {@code price}·{@code placeAddr} 같은 상세 필드는 이 응답에 <b>존재하지 않는다.</b>
 *
 * <p><b>중첩 {@code Items} 우회</b>: {@code <items>} 하위에 {@code <item>}이 반복되는 구조를 record 필드에
 * {@code @JacksonXmlElementWrapper(localName="items")}로 직접 붙이면 레코드의 암묵 생성자 파라미터 이름 해석과
 * 충돌해 {@code InvalidDefinitionException}이 난다(Jackson 2.21.4·3.1.4 모두 재현 — 애노테이션의 {@code @Target}에
 * PARAMETER가 있어 컴포넌트 rename이 생성자 프로퍼티명까지 바꾼다). 중첩 {@code Items} 레코드로 감싸는 우회를
 * <b>유지해야 한다</b> — 평탄화하면 런타임에 터진다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CultureRealmListResponse(Header header, Body body) {

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
			String serviceName) {}

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
