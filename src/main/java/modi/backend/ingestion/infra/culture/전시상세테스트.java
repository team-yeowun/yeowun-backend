package modi.backend.ingestion.infra.culture;

import java.util.Optional;

import org.springframework.web.client.RestClient;

import modi.backend.domain.exhibition.catalog.CatalogDetailData;
import modi.backend.ingestion.config.KoreaCultureInformationClientConfig;
import modi.backend.ingestion.properties.PublicDataProperties;
import modi.backend.support.error.CoreException;

/**
 * 실제 공공데이터 전시 API(detail2 상세)를 호출해 눈으로 확인하는 <b>수동 실행용</b> 클래스 — IDE에서 Run.
 * <p>
 * 조회할 전시 seq는 실행 인자로 넘긴다(없으면 {@link #기본_SEQ}). IDE Run Configuration의 Program arguments에
 * 넣으면 된다. seq는 {@code 전시목록테스트} 출력의 대괄호 값이다.
 * <p>
 * 스프링 컨텍스트를 띄우지 않고 {@link KoreaCultureInformationClientConfig}의 빈 메서드를 직접 불러 조립하므로,
 * 운영과 <b>같은 RestClient 설정</b>(타임아웃·UTF-8 컨버터)을 탄다.
 * <p>
 * <b>주의</b>: 실제 외부 API를 호출한다(호출 한도 소모). 자동화된 검증은 테스트가 담당한다 —
 * MockWebServer 기반 {@code CultureExhibitionClientTest}, 실호출 E2E {@code CultureApiLiveE2ETest}.
 */
public class 전시상세테스트 {

	/** application.yaml의 기본값과 동일 — 환경변수가 있으면 그쪽이 이긴다. */
	private static final String 기본_BASE_URL = "https://apis.data.go.kr/B553457/cultureinfo";
	private static final String 기본_인증키 = "55aa921462823cce4b6aa1d36b0f5c318b0100a16af6fc56c89527e7353ad154";

	/** 부산현대미술관 "패트릭 블랑: 수직정원" — 2028년까지 이어지는 장기 전시라 표본으로 안정적이다. */
	private static final String 기본_SEQ = "319005";

	public static void main(String[] args) {
		String seq = args.length > 0 ? args[0] : 기본_SEQ;
		PublicDataProperties properties = new PublicDataProperties(
				env("CULTURE_API_BASE_URL", 기본_BASE_URL), env("CULTURE_API_KEY", 기본_인증키), 15L);

		System.out.println("=== 요청 ===");
		System.out.println("base-url: " + properties.baseUrl());
		System.out.println("인증키  : " + 가림(properties.serviceKey()));
		System.out.println("seq     : " + seq);

		RestClient restClient = new KoreaCultureInformationClientConfig()
				.koreaCultureInformationClient(properties);
		// 상세는 페이징이 없어 단건 호출 클라이언트만으로 충분하다(CultureCatalogReader는 순회·접기 담당).
		CultureExhibitionClient client =
				new CultureExhibitionClient(restClient, new CultureApiErrorHandler(), properties);

		long 시작 = System.nanoTime();
		try {
			// 상세가 없으면(없는 seq 등) 예외로 온다 — 아래 catch가 사유를 찍는다.
			modi.backend.ingestion.domain.data.CultureDetailPayload item = client.fetchDetail(seq);
			long 걸린ms = (System.nanoTime() - 시작) / 1_000_000;

			System.out.println();
			System.out.println("=== 결과 (" + 걸린ms + "ms) ===");

			CatalogDetailData data = item.toDetail();
			System.out.println("--- 도메인 값(변환 후) ---");
			System.out.println("가격      : " + data.price());
			System.out.println("장소 주소 : " + data.placeAddr());
			System.out.println("장소 seq  : " + data.placeSeq());
			System.out.println("설명(평문): " + 줄임(data.description()));

			System.out.println();
			System.out.println("--- 벤더 원문(verbatim — 스냅샷 적재분) ---");
			System.out.println(item);
		} catch (CoreException e) {
			// 이 원천은 HTTP 200을 주면서 본문 resultCode로 실패를 알린다 — 키 오류·한도 초과가 여기로 온다.
			System.out.println();
			System.out.println("!!! 호출 실패: " + e.getMessage());
			if (e.getCause() != null) {
				System.out.println("    원인: " + e.getCause());
			}
		}
	}

	private static String env(String 이름, String 기본값) {
		String 값 = System.getenv(이름);
		return 값 == null || 값.isBlank() ? 기본값 : 값;
	}

	/** 설명 본문은 길어서 콘솔을 덮는다 — 앞부분만 본다. */
	private static String 줄임(String 텍스트) {
		if (텍스트 == null) {
			return "(없음)";
		}
		String 한줄 = 텍스트.replaceAll("\\s+", " ").trim();
		return 한줄.length() <= 200 ? 한줄 : 한줄.substring(0, 200) + "… (총 " + 한줄.length() + "자)";
	}

	/** 키 전체를 콘솔에 뿌리지 않는다(화면 공유·로그 캡처 대비). */
	private static String 가림(String 인증키) {
		if (인증키 == null || 인증키.length() < 8) {
			return "(미설정)";
		}
		return 인증키.substring(0, 4) + "…" + 인증키.substring(인증키.length() - 4)
				+ " (" + 인증키.length() + "자)";
	}
}
