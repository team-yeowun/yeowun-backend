package modi.backend.ingestion.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 공공데이터포털 전시 API(한눈에보는문화정보, data.go.kr 15138937) 접속 설정.
 * {@code app.public-data.culture.*}를 바인딩한다. service-key는 시크릿 → 환경변수(CULTURE_API_KEY)로만 주입한다.
 *
 * @param baseUrl    엔드포인트 호스트+경로 (예: https://apis.data.go.kr/B553457/cultureinfo)
 * @param serviceKey 인증키(Decoding 키). 미설정 시 외부 호출을 시도하지 않고 스킵한다.
 * @param realmCode  분야 코드(기본 D000=전시)
 * @param numOfRows  1회 호출당 행 수
 * @param maxPages   동기화 시 순회할 최대 페이지 수(무한 호출 방지)
 * @param timeoutSeconds 응답 타임아웃(초). 게이트웨이가 TCP는 받고 응답을 안 줄 때 무한 대기하지 않도록 —
 *                       상세 백필이 단일 스케줄러 스레드에서 도는데 한 건이 멈추면 스레드·DB 커넥션이 영구 점유될 수 있어 필수.
 */
@ConfigurationProperties(prefix = "app.public-data.culture")
public record PublicDataProperties(
		String baseUrl,
		String serviceKey,
		String realmCode,
		Integer numOfRows,
		Integer maxPages,
		Long timeoutSeconds) {

	public PublicDataProperties {
		if (numOfRows == null || numOfRows <= 0) {
			numOfRows = 100;
		}
		if (maxPages == null || maxPages <= 0) {
			maxPages = 5;
		}
		if (realmCode == null || realmCode.isBlank()) {
			realmCode = "D000";
		}
		if (timeoutSeconds == null || timeoutSeconds <= 0) {
			timeoutSeconds = 15L;
		}
	}

	/** 인증키가 설정되어 있어야 외부 호출이 가능하다. */
	public boolean isConfigured() {
		return serviceKey != null && !serviceKey.isBlank()
				&& baseUrl != null && !baseUrl.isBlank();
	}
}
