package modi.backend.ingestion.infra.culture;

import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;

/**
 * 한눈에보는문화정보(15138937) realm2(목록)·detail2(상세) 선언형 클라이언트(HTTP Interface, RestClient 백엔드).
 * 응답이 XML이라 문자열로 받는다 — 파싱은 {@link CultureApiMapper}가 XmlMapper로 담당한다.
 */
public interface CultureApi {

	@GetExchange("/realm2")
	String getRealmList(
            @RequestParam String serviceKey,
            @RequestParam("PageNo") int pageNo,
			@RequestParam("numOfrows") int numOfRows,
            @RequestParam String realmCode
    );

	@GetExchange("/detail2")
	String getDetail(
            @RequestParam String serviceKey,
            @RequestParam String seq
    );
}
