package modi.backend.ingestionv2.collect.infra;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import modi.backend.ingestionv2.collect.domain.CollectErrorCode;
import modi.backend.support.error.CoreException;

/**
 * 문화포털 목록(realm2) 접속 담당.
 *
 * <ul>
 *   <li>접속 설정(base URL·인증키·타임아웃) 소유 - "무엇을 얼마나"는 어댑터가 인자로 내려보냄</li>
 *   <li>벤더 실패 판정 포함 - 이 원천은 키 오류·한도 초과도 HTTP 200 + resultCode 로 보냄</li>
 *   <li>전송·역직렬화·벤더 실패를 하나의 오류 어휘로 번역 (재시도 판정은 상위 몫)</li>
 *   <li>상세(detail2)는 보강 격벽이 자기 어댑터로 부른다 - 격벽 사이의 직접 참조를 만들지 않기 위해 요청선을 나눔</li>
 * </ul>
 */
@Component
public class CultureApiCaller {

	/** 연결 수립 상한(읽기 타임아웃과 별개). */
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

	private final RestClient restClient;
	private final String serviceKey;

	public CultureApiCaller(
			@Value("${app.public-data.culture.base-url:https://apis.data.go.kr/B553457/cultureinfo}") String baseUrl,
			@Value("${app.public-data.culture.service-key:}") String serviceKey,
			@Value("${app.public-data.culture.timeout-seconds:15}") long timeoutSeconds) {
		this.serviceKey = serviceKey;
		HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		// 무응답 stall 시 워커 스레드·DB 커넥션 영구 점유 방지.
		requestFactory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
		this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
	}

	/** 목록 한 페이지 - 페이지 순회는 어댑터가 담당한다. */
	public CultureApiDto.ListResponse list(String realmCode, String serviceType, String sortOrder, int page,
			int pageSize) {
		requireServiceKey();
		CultureApiDto.ListResponse response = exchange(() -> restClient.get()
				.uri(uriBuilder -> uriBuilder.path("/realm2")
						.queryParam("serviceKey", serviceKey)
						.queryParam("PageNo", page)
						.queryParam("numOfrows", pageSize)
						.queryParam("realmCode", realmCode)
						.queryParam("serviceTp", serviceType)
						.queryParam("sortStdr", sortOrder)
						.build())
				.retrieve()
				.body(CultureApiDto.ListResponse.class), "/realm2");
		if (response == null || !response.isSuccess()) {
			throw vendorError("/realm2", response == null ? "응답 없음" : response.vendorFailure());
		}
		return response;
	}

	/** 인증키 미설정은 호출 전 실패 - 부르지 않은 호출을 감사·원장에 남기지 않게 여기서 끊는다. */
	private void requireServiceKey() {
		if (serviceKey == null || serviceKey.isBlank()) {
			throw new CoreException(CollectErrorCode.EXTERNAL_CALL_FAILED, "문화포털 인증키 미설정");
		}
	}

	private <T> T exchange(Supplier<T> call, String path) {
		try {
			return call.get();
		} catch (RuntimeException failure) {
			throw new CoreException(CollectErrorCode.EXTERNAL_CALL_FAILED,
					"문화포털 호출 실패 " + path + ": " + failure.getMessage(), failure);
		}
	}

	private CoreException vendorError(String path, String failure) {
		return new CoreException(CollectErrorCode.EXTERNAL_CALL_FAILED, "문화포털 비정상 응답 " + path + ": " + failure);
	}
}
