package modi.backend.ingestion.infra.culture;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

import org.springframework.web.client.RestClient;

import modi.backend.ingestion.config.KoreaCultureInformationClientConfig;
import modi.backend.ingestion.domain.CatalogServiceType;
import modi.backend.ingestion.domain.CatalogSortOrder;
import modi.backend.ingestion.domain.ExhibitionRealm;
import modi.backend.domain.exhibition.catalog.ExhibitionRegion;
import modi.backend.ingestion.domain.data.CatalogExhibitionData;
import modi.backend.ingestion.domain.data.CatalogFetchCriteria;
import modi.backend.ingestion.domain.data.CatalogFetchFilter;
import modi.backend.ingestion.domain.data.CatalogPage;
import modi.backend.ingestion.properties.PublicDataProperties;
import modi.backend.support.error.CoreException;

/**
 * 실제 공공데이터 전시 API(realm2 목록)를 호출해 눈으로 확인하는 <b>수동 실행용</b> 클래스 — IDE에서 Run.
 * <p>
 * 스프링 컨텍스트를 띄우지 않고 {@link KoreaCultureInformationClientConfig}의 빈 메서드를 직접 불러 조립하므로,
 * 운영과 <b>같은 RestClient 설정</b>(타임아웃·UTF-8 컨버터)을 탄다. 설정이 바뀌면 이 확인도 같이 바뀐다.
 * <p>
 * 인증키는 환경변수 {@code CULTURE_API_KEY}를 먼저 쓰고, 없으면 application.yaml의 기본값(팀 공용 공공데이터 키)을 쓴다.
 * <p>
 * <b>주의</b>: 이 클래스는 실제 외부 API를 호출한다(호출 한도 소모). 자동화된 검증은 테스트가 담당한다 —
 * MockWebServer 기반 {@code CultureExhibitionClientTest}, 실호출 E2E {@code CultureApiLiveE2ETest}.
 */
public class 전시목록테스트 {

	/** application.yaml의 기본값과 동일 — 환경변수가 있으면 그쪽이 이긴다. */
	private static final String 기본_BASE_URL = "https://apis.data.go.kr/B553457/cultureinfo";
	private static final String 기본_인증키 = "55aa921462823cce4b6aa1d36b0f5c318b0100a16af6fc56c89527e7353ad154";

	/** 눈으로 보기 좋은 크기 — 운영 기본값(100/500)이 아니라 작게 잡는다. 페이지 순회를 보려면 상한을 페이지 크기보다 크게. */
	private static final int 페이지_크기 = 2;
	private static final int 수집_상한 = 10;

	/**
	 * 선택 필터 — <b>null로 두면 그 파라미터는 요청에 아예 안 붙는다.</b> 바꿔가며 확인해 보라.
	 * (실측: 지역 부산 → 15건 · 장소 "국립" 부분일치 → 58건 · 검색어 "미술" → 41건)
	 */
	private static final CatalogFetchFilter 필터 = new CatalogFetchFilter(
			null,   // 지역(시/도)      예: ExhibitionRegion.BUSAN
			null,   // 기간 시작        예: LocalDate.of(2026, 1, 1)
			null,   // 기간 종료        예: LocalDate.of(2026, 12, 31)
			null,   // 장소(부분일치)    예: "국립"
			null,   // 검색어(부분일치)  예: "미술"
			null);  // 좌표 범위        예: new CatalogFetchFilter.Bounds(128.8, 35.0, 129.2, 35.3)

	/**
	 * 수집한 뒤 <b>우리가</b> 한 번 더 정렬할지 — 보통은 불필요하다({@link #정렬_기준}으로 원천이 이미 정렬해 준다).
	 * <p>
	 * 원천 정렬을 못 믿을 상황(예: 문서에 없는 코드가 무효화됨)에서 눈으로 대조할 때만 켠다.
	 * 켤 경우 {@link #수집_상한}을 원천 총 건수 이상으로 올려야 의미가 있다(전량을 받아야 정렬이 정확하다).
	 */
	private static final boolean 최신순_정렬 = false;

	/** 필수 조건 — 원천이 항상 요구하므로 null로 둘 수 없다. */
	private static final CatalogServiceType 분야별_구분 = CatalogServiceType.PERFORMANCE_EXHIBITION;
	private static final CatalogSortOrder 정렬_기준 = CatalogSortOrder.START_DATE_DESC;

	public static void main(String[] args) {
		PublicDataProperties properties = new PublicDataProperties(
				env("CULTURE_API_BASE_URL", 기본_BASE_URL), env("CULTURE_API_KEY", 기본_인증키), 15L);
		CatalogFetchCriteria criteria = new CatalogFetchCriteria(
				ExhibitionRealm.EXHIBITION, 분야별_구분, 정렬_기준, 페이지_크기, 수집_상한, 필터);

		System.out.println("=== 요청 ===");
		System.out.println("base-url  : " + properties.baseUrl());
		System.out.println("인증키    : " + 가림(properties.serviceKey()));
		System.out.println("분야      : " + criteria.realm()
				+ " / 분야별구분=" + criteria.serviceType() + " / 정렬=" + criteria.sortOrder());
		System.out.printf("페이지크기: %d / 수집상한: %d (→ 최대 %d콜)%n",
				criteria.pageSize(), criteria.maxItems(), criteria.maxCalls());
		System.out.println("필터      : " + 필터설명(criteria.filter()));

		RestClient restClient = new KoreaCultureInformationClientConfig()
				.koreaCultureInformationClient(properties);
		CultureExhibitionClient client = new CultureExhibitionClient(restClient, new CultureApiErrorHandler(), properties);

		long 시작 = System.nanoTime();
		try {
			// 수동 확인이라 1페이지만 본다(순회는 이제 CatalogSynchronizer의 몫이다).
			CatalogPage result = client.fetchPage(criteria, 1);
            System.out.println(result);
			long 걸린ms = (System.nanoTime() - 시작) / 1_000_000;

			System.out.println();
			System.out.println("=== 결과 (" + 걸린ms + "ms) ===");
			System.out.println("원천이 말한 총 건수 : " + (result.totalCount() == null ? "모름(응답에 없음)" : result.totalCount()));
			System.out.println("적재 가능 수집 건수 : " + result.items().size());

			System.out.println();
			System.out.println("=== 수집 항목" + (최신순_정렬 ? " (최신순 — 수집 후 정렬)" : " (원천 순서 그대로)") + " ===");
			List<CatalogExhibitionData> items = result.items();
			if (최신순_정렬) {
				// 시작일 없는 행은 뒤로 보낸다(원천 결측 — 정렬 기준이 없다).
				items = items.stream()
						.sorted(Comparator.comparing(CatalogExhibitionData::startDate,
								Comparator.nullsLast(Comparator.reverseOrder())))
						.toList();
			}
			for (int i = 0; i < items.size(); i++) {
				CatalogExhibitionData item = items.get(i);
				System.out.printf("%2d. [%s] %s%n", i + 1, item.externalId(), item.title());
				System.out.printf("     기간 %s ~ %s / 장소 %s / 지역 %s(%s)%n",
						item.startDate(), item.endDate(), item.place(), item.region(), item.sigungu());
			}

			System.out.println();
			System.out.println("=== 스냅샷 적재 원천(첫 건) ===");
			items.stream().findFirst().ifPresentOrElse(
					data -> System.out.println(data),
					() -> System.out.println("(수집된 항목 없음)"));
		} catch (CoreException e) {
			// 이 원천은 HTTP 200을 주면서 본문 resultCode로 실패를 알린다 — 키 오류·한도 초과가 여기로 온다.
			System.out.println();
			System.out.println("!!! 호출 실패: " + e.getMessage());
			if (e.getCause() != null) {
				System.out.println("    원인: " + e.getCause());
			}
		}
	}

	/** 값이 있는 필터만 보여준다 — 요청선에 실제로 붙는 것과 일치한다. */
	private static String 필터설명(CatalogFetchFilter f) {
		StringBuilder sb = new StringBuilder();
		if (f.region() != null) sb.append("지역=").append(f.region()).append(' ');
		if (f.from() != null) sb.append("시작≥").append(f.from()).append(' ');
		if (f.to() != null) sb.append("종료≤").append(f.to()).append(' ');
		if (f.place() != null) sb.append("장소~").append(f.place()).append(' ');
		if (f.keyword() != null) sb.append("검색어~").append(f.keyword()).append(' ');
		if (f.bounds() != null) sb.append("좌표범위 ");
		return sb.isEmpty() ? "(없음 — 선택 파라미터를 하나도 보내지 않는다)" : sb.toString().trim();
	}

	private static String env(String 이름, String 기본값) {
		String 값 = System.getenv(이름);
		return 값 == null || 값.isBlank() ? 기본값 : 값;
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
