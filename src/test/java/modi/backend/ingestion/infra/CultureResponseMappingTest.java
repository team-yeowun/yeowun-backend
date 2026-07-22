package modi.backend.ingestion.infra;

import modi.backend.ingestion.infra.culture.KoreaCultureDto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import tools.jackson.dataformat.xml.XmlMapper;
import org.junit.jupiter.api.Test;

import modi.backend.domain.exhibition.catalog.CatalogDetailData;
import modi.backend.ingestion.domain.data.CatalogExhibitionData;
import java.time.LocalDate;

import modi.backend.domain.exhibition.catalog.ExhibitionRegion;
import modi.backend.support.error.CoreException;

/**
 * 응답 record의 <b>도메인 매핑 팩토리</b>({@code toCatalog()}·{@code toDetail()}) 순수 단위 테스트(HTTP 없음) —
 * XML 문자열 → 응답 레코드 파싱 +
 * 도메인({@link CatalogExhibitionData}/{@link CatalogDetailData}) 매핑을 검증한다.
 * 벤더 실패 판정은 {@code CultureApiErrorHandlerTest}가 본다(책임 분리).
 * 샘플은 {@link CultureResponseBindingTest}·{@link CultureExhibitionClientTest}와 동일 표본(seq=319005, 부산/사하구)을 재사용한다.
 */
class CultureResponseMappingTest {

	private static final String REALM2_XML = "<response><header><resultCode>00</resultCode><resultMsg>정상입니다.</resultMsg></header>"
			+ "<body><totalCount>1</totalCount><PageNo>1</PageNo><numOfrows>100</numOfrows><items>"
			+ "<item><serviceName>전시</serviceName><seq>319005</seq><title>패트릭 블랑</title>"
			+ "<startDate>20180616</startDate><endDate>20281231</endDate><place>부산현대미술관</place>"
			+ "<realmName>전시</realmName><area>부산</area><sigungu>사하구</sigungu>"
			+ "<thumbnail>http://t/x.jpg</thumbnail><gpsX>128.9</gpsX><gpsY>35.1</gpsY></item>"
			+ "</items></body></response>";

	private static final String DETAIL2_XML = "<response><header><resultCode>00</resultCode><resultMsg>정상입니다.</resultMsg></header>"
			+ "<body><totalCount>1</totalCount><items>"
			+ "<item><seq>319005</seq><title>패트릭 블랑</title><price>무료</price>"
			+ "<contents1>설명입니다.</contents1><url>http://detail/319005</url><phone>051-000-0000</phone>"
			+ "<imgUrl>http://img/x.jpg</imgUrl><placeUrl>http://place/x</placeUrl>"
			+ "<placeAddr>부산광역시 사하구 낙동남로 1191</placeAddr><placeSeq>P1</placeSeq></item>"
			+ "</items></body></response>";

	private static final String ERROR_XML = "<response><header><resultCode>99</resultCode><resultMsg>ERR</resultMsg></header><body/></response>";

	private static final String REALM2_ESCAPED_XML = "<response><header><resultCode>00</resultCode><resultMsg>정상입니다.</resultMsg></header>"
			+ "<body><totalCount>1</totalCount><PageNo>1</PageNo><numOfrows>100</numOfrows><items>"
			+ "<item><serviceName>전시</serviceName><seq>319006</seq><title>이사라 &amp;lt;A Girl From Wonderland&amp;gt;</title>"
			+ "<startDate>20180616</startDate><endDate>20281231</endDate><place>서울 &amp;amp; 경기 전시관</place>"
			+ "<realmName>전시</realmName><area>부산</area><sigungu>사하구</sigungu>"
			+ "<thumbnail>http://t/x.jpg</thumbnail><gpsX>128.9</gpsX><gpsY>35.1</gpsY></item>"
			+ "</items></body></response>";

	private static final String DETAIL2_ESCAPED_XML = "<response><header><resultCode>00</resultCode><resultMsg>정상입니다.</resultMsg></header>"
			+ "<body><totalCount>1</totalCount><items>"
			+ "<item><seq>319006</seq><title>이사라 &amp;lt;A Girl From Wonderland&amp;gt;</title><price>무료</price>"
			+ "<contents1>첫째 줄입니다.&amp;lt;br/&amp;gt;둘째 줄입니다.</contents1><url>http://detail/319006</url><phone>051-000-0000</phone>"
			+ "<imgUrl>http://img/x.jpg</imgUrl><placeUrl>http://place/x</placeUrl>"
			+ "<placeAddr>부산광역시 사하구 낙동남로 1191</placeAddr><placeSeq>P1</placeSeq></item>"
			+ "</items></body></response>";

	// 실제 원천 표본: 워드프레스 블록 주석 + <p>/<span style> 태그로 감싼 본문(단일 이스케이프). 배민정 전시 실데이터 축약.
	private static final String DETAIL2_WORDPRESS_XML = "<response><header><resultCode>00</resultCode><resultMsg>정상입니다.</resultMsg></header>"
			+ "<body><totalCount>1</totalCount><items>"
			+ "<item><seq>319007</seq><title>TRACE</title><price>무료</price>"
			+ "<contents1>&lt;!-- wp:paragraph --&gt;&lt;p&gt;배민정 작가는 자신의 일상에서 떠오른 감성적 주제를 AI에 입력한다.&lt;/p&gt;&lt;!-- /wp:paragraph --&gt;"
			+ "&lt;!-- wp:paragraph --&gt;&lt;p&gt;이번 전시 [ TRACE : 생성 과정의 잔여 ]에서는 &lt;span style=&quot;font-size: 10pt;&quot;&gt;잔여&lt;/span&gt;에 주목한다.&lt;/p&gt;&lt;!-- /wp:paragraph --&gt;</contents1>"
			+ "<url>http://detail/319007</url></item>"
			+ "</items></body></response>";

	// 이중 이스케이프 표본(&amp;lt;p&amp;gt;) — XML 파싱 1단계 + 재해제 1단계로 태그까지 벗겨져야 한다.
	private static final String DETAIL2_DOUBLE_ESCAPED_XML = "<response><header><resultCode>00</resultCode><resultMsg>정상입니다.</resultMsg></header>"
			+ "<body><totalCount>1</totalCount><items>"
			+ "<item><seq>319008</seq><title>이중</title><price>무료</price>"
			+ "<contents1>&amp;lt;p style=&amp;quot;line-height:1.8;&amp;quot;&amp;gt;소장품 이야기&amp;lt;/p&amp;gt;</contents1>"
			+ "<url>http://detail/319008</url></item>"
			+ "</items></body></response>";

	/** 역직렬화는 이제 운영에서 Spring XML 컨버터가 한다 — 테스트는 같은 Jackson 3 XmlMapper로 픽스처만 만든다. */
	private static final XmlMapper XML = new XmlMapper();

	private static KoreaCultureDto.Realm2ListResponse parseList(String xml) {
		return XML.readValue(xml, KoreaCultureDto.Realm2ListResponse.class);
	}

	private static KoreaCultureDto.Detail2Response parseDetail(String xml) {
		return XML.readValue(xml, KoreaCultureDto.Detail2Response.class);
	}

	@Test
	@DisplayName("toCatalog — 목록 응답 12필드가 하나도 빠짐없이 옮겨진다(스냅샷 적재 원천이므로 누락 = 증거 손실)")
	void toCatalog_전필드_이관() {
		KoreaCultureDto.Realm2ListResponse.Item item = parseList(REALM2_XML).items().get(0);

		CatalogExhibitionData data = item.toCatalog();

		// 실측(realm2 12필드)이 확정한 목록 응답의 전 필드가 이 record에 남아야 한다 — culture_list_snapshot이
		// 이걸 그대로 적재하므로, 하나라도 빠지면 그 컬럼이 영영 빈다(별도 verbatim 어휘가 더는 없다).
		assertThat(data.externalId()).isEqualTo("319005");
		assertThat(data.title()).isEqualTo("패트릭 블랑");
		assertThat(data.startDate()).isEqualTo(LocalDate.of(2018, 6, 16));
		assertThat(data.endDate()).isEqualTo(LocalDate.of(2028, 12, 31));
		assertThat(data.place()).isEqualTo("부산현대미술관");
		assertThat(data.realmName()).isEqualTo("전시");
		assertThat(data.areaText()).isEqualTo("부산");
		assertThat(data.sigungu()).isEqualTo("사하구");
		assertThat(data.posterUrl()).isEqualTo("http://t/x.jpg");
		assertThat(data.gpsX()).isEqualTo(128.9);
		assertThat(data.gpsY()).isEqualTo(35.1);
		assertThat(data.serviceName()).isEqualTo("전시");
	}

	@Test
	@DisplayName("toDetail — 워드프레스 HTML 본문을 읽기 좋은 평문으로 만든다(원문은 스냅샷이 보관한다)")
	void toDetail_HTML_평문화() {
		KoreaCultureDto.Detail2Response.Item item = parseDetail(DETAIL2_WORDPRESS_XML).items().get(0);

		// 도메인 값은 평문. 변환 전 원문은 어댑터가 CultureDetailSnapshot에 그대로 적재한다.
		assertThat(item.toDetail().description()).doesNotContain("wp:paragraph");
		assertThat(item.contents1()).contains("wp:paragraph"); // 응답 원문에는 남아 있다
	}

	@Test
	@DisplayName("toCatalog — area→region=BUSAN·sigungu·realmName·areaText 매핑")
	void toCatalog_매핑() {
		KoreaCultureDto.Realm2ListResponse.Item item = parseList(REALM2_XML).items().get(0);

		CatalogExhibitionData data = item.toCatalog();

		assertThat(data.externalId()).isEqualTo("319005");
		assertThat(data.region()).isEqualTo(ExhibitionRegion.BUSAN);
		assertThat(data.sigungu()).isEqualTo("사하구");
		assertThat(data.realmName()).isEqualTo("전시");
		assertThat(data.areaText()).isEqualTo("부산");
		assertThat(data.startDate()).isNotNull();
	}

	@Test
	@DisplayName("toDetail — price·placeAddr·placeSeq 매핑")
	void toDetail_매핑() {
		KoreaCultureDto.Detail2Response.Item item = parseDetail(DETAIL2_XML).items().get(0);

		CatalogDetailData detail = item.toDetail();

		assertThat(detail.price()).isEqualTo("무료");
		assertThat(detail.placeAddr()).isEqualTo("부산광역시 사하구 낙동남로 1191");
		assertThat(detail.placeSeq()).isEqualTo("P1");
	}

	@Test
	@DisplayName("toCatalog — 원본이 HTML 엔티티로 이스케이프돼 있으면 디코딩해 저장한다")
	void toCatalog_HTML_엔티티_디코딩() {
		KoreaCultureDto.Realm2ListResponse.Item item = parseList(REALM2_ESCAPED_XML).items().get(0);

		CatalogExhibitionData data = item.toCatalog();

		assertThat(data.title()).isEqualTo("이사라 <A Girl From Wonderland>");
		assertThat(data.place()).isEqualTo("서울 & 경기 전시관");
	}

	@Test
	@DisplayName("toDetail — description의 HTML 엔티티를 디코딩하고 br 태그를 줄바꿈으로 정리한다")
	void toDetail_HTML_엔티티_디코딩() {
		KoreaCultureDto.Detail2Response.Item item = parseDetail(DETAIL2_ESCAPED_XML).items().get(0);

		CatalogDetailData detail = item.toDetail();

		assertThat(detail.description()).isEqualTo("첫째 줄입니다.\n둘째 줄입니다.");
	}

	@Test
	@DisplayName("toDetail — 워드프레스 블록 주석·<p>·<span> 태그를 벗겨 읽기 좋은 평문으로 만든다")
	void toDetail_워드프레스_태그제거() {
		KoreaCultureDto.Detail2Response.Item item = parseDetail(DETAIL2_WORDPRESS_XML).items().get(0);

		CatalogDetailData detail = item.toDetail();

		String desc = detail.description();
		// 태그·주석·이스케이프 잔재가 남지 않아야 한다
		assertThat(desc).doesNotContain("<").doesNotContain("&lt;").doesNotContain("wp:paragraph")
				.doesNotContain("style=");
		// 본문 텍스트는 보존되고 문단은 개행으로 구분된다
		assertThat(desc).contains("배민정 작가는").contains("TRACE : 생성 과정의 잔여").contains("잔여에 주목한다");
		assertThat(desc).isEqualTo("배민정 작가는 자신의 일상에서 떠오른 감성적 주제를 AI에 입력한다.\n"
				+ "이번 전시 [ TRACE : 생성 과정의 잔여 ]에서는 잔여에 주목한다.");
	}

	@Test
	@DisplayName("toDetail — 이중 이스케이프(&lt;p&gt;)된 본문도 태그까지 완전히 벗겨낸다")
	void toDetail_이중이스케이프_태그제거() {
		KoreaCultureDto.Detail2Response.Item item = parseDetail(DETAIL2_DOUBLE_ESCAPED_XML).items().get(0);

		CatalogDetailData detail = item.toDetail();

		assertThat(detail.description()).isEqualTo("소장품 이야기");
	}
}
