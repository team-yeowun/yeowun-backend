package modi.backend.ingestion.infra.culture;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import org.springframework.web.util.HtmlUtils;

import modi.backend.support.text.HtmlTextExtractor;

/**
 * 한눈에보는문화정보 응답 필드를 도메인 값으로 정제하는 <b>변환 규칙 모음</b>(infra 전용).
 *
 * <p>목록({@link CultureRealm2ListResponse.Item})과 상세({@link CultureDetail2Response.Item})가 <b>같은 규칙을
 * 공유</b>한다 — 각 record에 복사해 두면 "XML 이스케이프 원복" 같은 규칙이 두 벌이 되어 한쪽만 고치는 사고가 난다.
 *
 * <p>도메인이 아니라 여기 있는 이유: {@link HtmlUtils}(Spring)에 의존하고, 다루는 대상이 <b>이 벤더의 표기</b>
 * (YYYYMMDD 8자리·워드프레스 블록 HTML)이기 때문이다. 원천이 표기를 바꾸면 이 파일만 바뀐다.
 */
final class CultureFieldCodec {

	private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

	private CultureFieldCodec() {
	}

	/** 빈 문자열·공백은 결측(null)으로 본다 — 원천이 값 없음을 빈 태그로 표현한다. */
	static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	/**
	 * 공공데이터 원문이 XML-escape된 채로 내려오므로(예: {@code &lt;A Girl&gt;}), 사람이 읽을 텍스트 필드는
	 * 저장 전 원래 값으로 되돌린다. 의미를 바꾸는 가공이 아니라 소스가 이스케이프한 것을 원복하는 정규화다.
	 */
	static String decode(String value) {
		// 모든 HTML4 명명·숫자 엔티티(&lt; &amp; &middot; &ndash; &#nnn; 등)를 스프링 표준 디코더로 일괄 처리한다.
		return value == null ? null : HtmlUtils.htmlUnescape(value);
	}

	/**
	 * {@code contents1}(설명)은 원천이 워드프레스 블록/HTML(예: {@code <!-- wp:paragraph --><p style="…">…</p>})로
	 * 내려주므로 {@link HtmlTextExtractor}로 태그를 벗겨 <b>읽기 좋은 평문</b>으로 만든다
	 * (최초 수집 파싱과 기존 데이터 재파싱이 같은 규칙을 공유한다).
	 */
	static String decodeDescription(String value) {
		return HtmlTextExtractor.toPlainText(value);
	}

	/** YYYYMMDD 8자리만 파싱, 그 외·결측은 null. */
	static LocalDate parseDate(String value) {
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

	/** 좌표 문자열 → Double. 결측·비수치는 null(좌표 없음과 0.0은 다르다). */
	static Double parseCoordinate(String value) {
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
