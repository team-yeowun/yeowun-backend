package modi.backend.ingestion.infra.culture;

import static modi.backend.domain.exhibition.catalog.ExhibitionErrorCode.EXTERNAL_API_UNAVAILABLE;

import org.springframework.stereotype.Component;

import modi.backend.domain.exhibition.catalog.ExhibitionErrorCode;
import modi.backend.support.error.CoreException;

/**
 * 한눈에보는문화정보(15138937) 응답의 <b>벤더 실패를 우리 예외로 번역</b>하는 전담 컴포넌트.
 *
 * <p><b>왜 HTTP 상태 핸들러로 대체할 수 없나</b>: 이 원천은 실패를 <b>HTTP 200으로</b> 보낸다 — 키 오류·한도 초과·
 * 기한 만료가 전부 {@code 200 OK} + 본문 {@code <header><resultCode>}에 담겨 온다. 그래서
 * {@code RestClient.onStatus(...)}는 <b>어떤 조건을 걸어도 발동하지 않는다</b>. 성공 여부는 상태코드가 아니라
 * 본문 코드로만 판정할 수 있고, 그 판정이 이 클래스다.
 *
 * <p><b>왜 빼먹으면 안 되나</b>: 역직렬화는 성공해도 내용은 실패일 수 있다. 이 검사를 건너뛰면 한도 초과 응답이
 * <b>빈 목록으로 파싱되어 조용히 "0건 수집"</b>으로 지나간다 — 예외도 경고도 남지 않는다.
 *
 * <p>매핑(응답 record의 {@code toCatalog()}·{@code toDetail()})과 <b>분리한 이유</b>: 하는 일이 "벤더 어휘를 도메인으로 옮기는 것"이 아니라
 * "벤더가 실패라고 말했는지 읽고 예외로 바꾸는 것"이라 변경 이유가 다르다. 원천이 결과코드 체계를 바꾸면 여기만,
 * 응답 필드가 바뀌면 매퍼만 바뀐다.
 */
@Component
public class CultureApiErrorHandler {

	/**
	 * 목록(realm2) 응답이 벤더 실패면 {@link ExhibitionErrorCode#EXTERNAL_API_UNAVAILABLE}로 던진다.
	 * 응답을 객체로 받은 <b>직후</b> 통과시켜야 한다.
	 */
	public void throwIfVendorError(CultureRealm2ListResponse response) {
		throwIfVendorError(response == null, response == null ? null : response.resultCode(),
				response != null && response.isSuccess());
	}

	/** 상세(detail2) 응답 — 판정 규칙은 목록과 같다(응답 타입만 다르다). */
	public void throwIfVendorError(CultureDetail2Response response) {
		throwIfVendorError(response == null, response == null ? null : response.resultCode(),
				response != null && response.isSuccess());
	}

	/**
	 * 응답 본문({@code <body>})이 통째로 없는 실패 응답도 있으므로 {@code header}의 결과코드만으로 판정한다.
	 * 실패 사유는 <b>사람이 읽는 라벨</b>로 남긴다 — 운영 로그에서 "왜 실패했나"(한도초과 vs 키오류)를 코드 암기 없이 판독한다.
	 */
	private void throwIfVendorError(boolean absent, String resultCode, boolean success) {
		if (absent) {
			throw new CoreException(EXTERNAL_API_UNAVAILABLE, "외부 전시 API 응답 없음");
		}
		if (!success) {
			throw new CoreException(EXTERNAL_API_UNAVAILABLE,
					"외부 전시 API 비정상: " + CultureResultCode.describe(resultCode));
		}
	}
}
