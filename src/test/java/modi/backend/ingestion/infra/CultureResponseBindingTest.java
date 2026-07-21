package modi.backend.ingestion.infra;

import modi.backend.ingestion.infra.culture.CultureRealm2ListResponse;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.dataformat.xml.XmlMapper;
import org.junit.jupiter.api.Test;

class CultureResponseBindingTest {
    private final XmlMapper xml = new XmlMapper();

    @Test
    void 성공응답_파싱() throws Exception {
        String body = "<response><header><resultCode>00</resultCode><resultMsg>정상입니다.</resultMsg></header>"
            + "<body><totalCount>266</totalCount><PageNo>1</PageNo><numOfrows>1</numOfrows><items>"
            + "<item><serviceName>전시</serviceName><seq>319005</seq><title>패트릭 블랑</title>"
            + "<startDate>20180616</startDate><endDate>20281231</endDate><place>부산현대미술관</place>"
            + "<realmName>전시</realmName><area>부산</area><sigungu>사하구</sigungu>"
            + "<thumbnail>http://t/x.jpg</thumbnail><gpsX>128.9</gpsX><gpsY>35.1</gpsY></item>"
            + "</items></body></response>";
        CultureRealm2ListResponse res = xml.readValue(body, CultureRealm2ListResponse.class);
        assertThat(res.isSuccess()).isTrue();
        assertThat(res.items()).hasSize(1);
        assertThat(res.items().get(0).seq()).isEqualTo("319005");
        assertThat(res.items().get(0).area()).isEqualTo("부산");
    }

    @Test
    void 에러응답_isSuccess_false() throws Exception {
        String body = "<response><header><resultCode>99</resultCode><resultMsg>ERR</resultMsg></header><body/></response>";
        assertThat(xml.readValue(body, CultureRealm2ListResponse.class).isSuccess()).isFalse();
    }
}
