package modi.backend.ingestion.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.dataformat.xml.XmlMapper;

import modi.backend.ingestion.infra.culture.CultureApiErrorHandler;
import modi.backend.ingestion.infra.culture.KoreaCultureDto;
import modi.backend.support.error.CoreException;

/**
 * {@link CultureApiErrorHandler} 순수 단위 테스트 — <b>벤더가 200으로 보낸 실패</b>를 예외로 번역하는지 본다.
 * <p>
 * 이 검사가 빠지면 한도 초과 응답이 빈 목록으로 파싱되어 조용히 "0건 수집"으로 지나간다. HTTP 상태는 200이라
 * {@code RestClient.onStatus(...)}로는 잡을 수 없다 — 그래서 본문 결과코드를 보는 이 경로가 유일한 방어선이다.
 */
class CultureApiErrorHandlerTest {

	private static final String REALM2_XML = "<response><header><resultCode>00</resultCode><resultMsg>정상입니다.</resultMsg></header>"
			+ "<body><totalCount>1</totalCount><items>"
			+ "<item><seq>319005</seq><title>패트릭 블랑</title><area>부산</area></item>"
			+ "</items></body></response>";

	private static final String ERROR_XML =
			"<response><header><resultCode>99</resultCode><resultMsg>ERR</resultMsg></header><body/></response>";

	private final CultureApiErrorHandler errorHandler = new CultureApiErrorHandler();
	private static final XmlMapper XML = new XmlMapper();

	private static KoreaCultureDto.Realm2ListResponse parseList(String xml) {
		return XML.readValue(xml, KoreaCultureDto.Realm2ListResponse.class);
	}

	@Test
	@DisplayName("정상 응답이면 통과한다(items 접근 가능)")
	void 정상응답_통과() {
		KoreaCultureDto.Realm2ListResponse response = parseList(REALM2_XML);

		assertThatCode(() -> errorHandler.throwIfVendorError(response)).doesNotThrowAnyException();

		assertThat(response.isSuccess()).isTrue();
		assertThat(response.items()).hasSize(1);
	}

	@Test
	@DisplayName("resultCode 비정상이면 EXTERNAL_API_UNAVAILABLE — 역직렬화는 성공해도 내용은 실패다")
	void 비정상_resultCode_예외() {
		assertThatThrownBy(() -> errorHandler.throwIfVendorError(parseList(ERROR_XML)))
				.isInstanceOf(CoreException.class);
	}

	@Test
	@DisplayName("body 없이 header만 온 실패 응답에서도 한도초과 사유를 읽어낸다")
	void body결측_실패응답_사유판독() {
		String 한도초과 = "<response><header><resultCode>22</resultCode>"
				+ "<resultMsg>서비스 요청제한횟수 초과</resultMsg></header></response>";

		assertThatThrownBy(() -> errorHandler.throwIfVendorError(parseList(한도초과)))
				.isInstanceOf(CoreException.class)
				.hasMessageContaining("LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR");
	}

	@Test
	@DisplayName("응답 자체가 없으면(null) 예외 — '빈 목록'과 구분한다")
	void 응답없음_예외() {
		assertThatThrownBy(() -> errorHandler.throwIfVendorError((KoreaCultureDto.Realm2ListResponse) null))
				.isInstanceOf(CoreException.class)
				.hasMessageContaining("응답 없음");
	}
}
