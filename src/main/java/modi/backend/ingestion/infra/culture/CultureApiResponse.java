package modi.backend.ingestion.infra.culture;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import tools.jackson.dataformat.xml.annotation.JacksonXmlProperty;

/**
 * 한눈에보는문화정보(15138937) realm2/detail2 XML 응답 매핑.
 *
 * <p>{@code <items>} 하위에 {@code <item>}이 반복되는 구조를, jackson-dataformat-xml에서(Jackson 2.21.4·3.1.4 모두 재현)
 * Java record 필드에 {@code @JacksonXmlElementWrapper(localName="items")}를 직접 붙이면
 * 레코드의 암묵 생성자 파라미터 이름 해석과 충돌해 {@code InvalidDefinitionException}이 발생한다
 * (JacksonXmlProperty/Wrapper의 {@code @Target}에 PARAMETER가 포함되어 레코드 컴포넌트 rename이
 * 생성자 프로퍼티명까지 바꿔버림). 중첩 {@code Items} 레코드로 "items" 엘리먼트 자체를 감싸고,
 * 그 안에서 {@code item}을 언래핑 리스트로 받는 우회로 동일 계약을 만족시킨다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CultureApiResponse(Header header, Body body) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Header(String resultCode, String resultMsg) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Body(Integer totalCount, Items items) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Items(
            @JacksonXmlElementWrapper(useWrapping = false)
            @JacksonXmlProperty(localName = "item") List<Item> item) {}

    /** 목록(realm2) 12필드 + 상세(detail2) 확장필드(목록 응답에선 null). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(String seq, String title, String startDate, String endDate, String place,
            String realmName, String area, String sigungu, String thumbnail, String gpsX, String gpsY,
            String serviceName, String price, String contents1, String url, String phone, String imgUrl,
            String placeUrl, String placeAddr, String placeSeq) {}

    public boolean isSuccess() {
        return header != null && CultureResultCode.isSuccess(header.resultCode());
    }

    public List<Item> items() {
        return body == null || body.items() == null || body.items().item() == null
                ? List.of()
                : body.items().item();
    }
}
