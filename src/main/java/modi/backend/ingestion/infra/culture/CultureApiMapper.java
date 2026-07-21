package modi.backend.ingestion.infra.culture;

import static modi.backend.domain.exhibition.catalog.ExhibitionErrorCode.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import modi.backend.ingestion.domain.data.CatalogDetailVendorItem;
import org.springframework.web.util.HtmlUtils;

import modi.backend.domain.exhibition.catalog.CatalogDetailData;
import modi.backend.ingestion.domain.data.CatalogExhibitionData;
import modi.backend.domain.exhibition.catalog.ExhibitionCategory;
import modi.backend.domain.exhibition.catalog.ExhibitionErrorCode;
import modi.backend.domain.exhibition.catalog.ExhibitionRegion;
import modi.backend.support.error.CoreException;
import modi.backend.support.text.HtmlTextExtractor;

/**
 * 한눈에보는문화정보(15138937) realm2/detail2 응답의 <b>정상 여부 판정 + 도메인 매핑</b> 전담(SRP).
 * <p>
 * XML → 객체 역직렬화는 더 이상 여기서 하지 않는다 — Spring의 XML 메시지 컨버터가 맡는다
 * ({@code RestClient.body(CultureApiResponse.class)}). 이 컴포넌트는 <b>그렇게 받은 객체</b>를 놓고
 * "정상 응답인가"({@link #verify})와 "응답 필드를 도메인으로 정규화"를 담당한다.
 */
@Component
public class CultureApiMapper {

	private static final Logger log = LoggerFactory.getLogger(CultureApiMapper.class);
	private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

	/**
	 * 응답이 <b>정상인지</b> 검사한다 — 실패면 {@link ExhibitionErrorCode#EXTERNAL_API_UNAVAILABLE}로 던진다.
	 * <p>
	 * <b>왜 별도 단계인가</b>: 이 원천은 HTTP 200을 주면서 실패 사유를 본문 {@code <resultCode>}에 담는다
	 * (키 오류·한도 초과 등). 역직렬화는 성공해도 내용은 실패일 수 있으므로, 응답을 객체로 받은 <b>직후</b>
	 * 반드시 이 검사를 통과시켜야 한다. 빠뜨리면 한도 초과가 조용히 "0건 수집"으로 지나간다.
	 * <p>
	 * 응답 본문({@code <body>})이 통째로 없는 실패 응답도 있으므로 {@code header}만으로 판정한다.
	 */
	public void verify(CultureRealmListResponse response) {
		verifyResult(response == null, response != null && response.isSuccess(),
				response == null ? null : response.resultCode());
	}

	/** 상세 응답 검증 — 목록과 판정 규칙이 같다(응답 타입만 다르다). */
	public void verify(CultureDetailResponse response) {
		verifyResult(response == null, response != null && response.isSuccess(),
				response == null ? null : response.resultCode());
	}

	private void verifyResult(boolean absent, boolean success, String resultCode) {
		if (absent) {
			throw new CoreException(EXTERNAL_API_UNAVAILABLE, "외부 전시 API 응답 없음");
		}
		if (!success) {
			// 표준 코드를 사람이 읽는 라벨로 남긴다 — 운영 로그에서 "왜 실패했나"(한도초과 vs 키오류)를 코드 암기 없이 판독.
			throw new CoreException(EXTERNAL_API_UNAVAILABLE,
					"외부 전시 API 비정상: " + CultureResultCode.describe(resultCode));
		}
	}

	/**
	 * 상세 응답 아이템을 벤더 스냅샷 어휘(원문 verbatim)로 옮긴다 — {@code culture_detail_snapshot} 적재용(ADR-13).
	 *
	 * <p>도메인 변환({@code decode()}·HTML 평문 추출) <b>이전</b> 값이라 재파싱 원료로서 온전하다 — 특히
	 * {@code contents1}의 워드프레스 HTML 원문이 보존되어, 평문 추출 규칙이 바뀌면 여기서 다시 뽑을 수 있다.
	 * 목록에는 대응물이 없다: 목록 스냅샷은 {@link CatalogExhibitionData}를 그대로 적재한다(평문 추출이 없어
	 * 되돌릴 원문이 사실상 없고, 두 벌을 나르는 중복이 컸다).
	 */
	public CatalogDetailVendorItem vendorOf(CultureDetailResponse.Item item) {
		if (item == null) {
			return null;
		}
		return new CatalogDetailVendorItem(item.seq(), item.title(), item.startDate(), item.endDate(), item.place(),
				item.realmName(), item.area(), item.sigungu(), item.gpsX(), item.gpsY(),
				item.price(), item.contents1(), item.url(), item.phone(), item.imgUrl(),
				item.placeUrl(), item.placeAddr(), item.placeSeq());
	}

	public CatalogExhibitionData toCatalog(CultureRealmListResponse.Item item) {
		return new CatalogExhibitionData(
				item.seq(),
				decode(item.title()),
				decode(blankToNull(item.place())),
				parseDate(item.startDate()),
				parseDate(item.endDate()),
				ExhibitionRegion.fromAreaText(item.area()),
				ExhibitionCategory.fromRealmName(item.realmName()),
				blankToNull(item.thumbnail()),
				null, // detailUrl — 목록 응답에는 없다(상세 조회가 채운다)
				blankToNull(item.serviceName()),
				parseCoordinate(item.gpsX()),
				parseCoordinate(item.gpsY()),
				blankToNull(item.sigungu()),
				decode(blankToNull(item.realmName())),
				decode(blankToNull(item.area())));
	}

	public CatalogDetailData toDetail(CultureDetailResponse.Item item) {
		return new CatalogDetailData(
				decode(blankToNull(item.price())), decodeDescription(blankToNull(item.contents1())), blankToNull(item.url()),
				decode(blankToNull(item.phone())), blankToNull(item.imgUrl()), blankToNull(item.placeUrl()),
				decode(blankToNull(item.placeAddr())), blankToNull(item.placeSeq()));
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	/**
	 * 공공데이터 원문이 XML-escape된 채로 내려오므로(예: {@code &lt;A Girl&gt;}), 사람이 읽을 텍스트 필드는
	 * 저장 전 원래 값으로 되돌린다. 의미를 바꾸는 가공이 아니라 소스가 이스케이프한 것을 원복하는 정규화다.
	 * 순서 주의: 명명 엔티티·숫자 엔티티를 먼저 풀고 {@code &amp;}는 마지막에 풀어야 이중 디코딩을 피한다.
	 */
	private static String decode(String value) {
		// 모든 HTML4 명명·숫자 엔티티(&lt; &amp; &middot; &ndash; &#nnn; 등)를 스프링 표준 디코더로 일괄 처리한다.
		return value == null ? null : HtmlUtils.htmlUnescape(value);
	}

	/**
	 * contents1(설명)은 원천이 워드프레스 블록/HTML(예: {@code <!-- wp:paragraph --><p style="…">…</p>})로 내려주므로
	 * {@link HtmlTextExtractor}로 태그를 벗겨 <b>읽기 좋은 평문</b>으로 만든다(최초 수집 파싱과 기존 데이터 재파싱이 같은 규칙 공유).
	 */
	private static String decodeDescription(String value) {
		return HtmlTextExtractor.toPlainText(value);
	}

	/** YYYYMMDD 8자리만 파싱, 그 외/결측은 null. */
	private static LocalDate parseDate(String value) {
		String text = blankToNull(value);
		if (text == null || text.length() != 8) {
			return null;
		}
		try {
			return LocalDate.parse(text, YYYYMMDD);
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static Double parseCoordinate(String value) {
		String text = blankToNull(value);
		if (text == null) {
			return null;
		}
		try {
			return Double.parseDouble(text);
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
