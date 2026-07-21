package modi.backend.ingestion.infra.culture;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import modi.backend.ingestion.domain.data.CatalogExhibitionData;
import modi.backend.ingestion.domain.data.CatalogFetchCriteria;
import modi.backend.ingestion.domain.data.CatalogListData;
import modi.backend.ingestion.domain.data.DetailFetch;
import modi.backend.ingestion.domain.ExternalApi;
import modi.backend.ingestion.domain.ExternalApiOutcome;
import modi.backend.ingestion.domain.entity.ExternalApiCallLog;
import modi.backend.ingestion.domain.port.CatalogPageStop;
import modi.backend.ingestion.domain.port.ExhibitionCatalogClient;
import modi.backend.ingestion.domain.port.ExternalApiCallLogRepository;

/**
 * {@link ExhibitionCatalogClient} 어댑터 — 한눈에보는문화정보(15138937)의 <b>페이징 모델</b>을 아는 유일한 곳이다(DIP).
 * <p>
 * 단건 호출(요청선 조립·전송·예외 변환)은 {@link CultureExhibitionClient}에 위임하고, 이 클래스는
 * <b>"어디까지 부를지"·"받아 온 것을 어떻게 접을지"·"호출을 감사에 남기는 것"</b>을 맡는다. 두 클래스의 변경
 * 이유가 갈린다 — 페이징·감사가 바뀌면 여기만, 요청선이 바뀌면 저기만 바뀐다.
 *
 * <p><b>왜 감사가 전송 클래스가 아니라 여기인가</b>: 전송 클래스의 책임은 "불러서 응답을 준다"까지다(사용자 결정).
 * 감사를 더 위(수집 유스케이스)로 올리는 것도 안 된다 — 거기선 {@code fetchAll} 1회가 3콜인 걸 볼 수 없어
 * 3콜이 1행으로 뭉개진다. <b>페이지 단위 호출자인 이 클래스가 그 경계를 가진 가장 바깥</b>이다.
 *
 * <p><b>왜 순회가 어댑터 안에 있나</b>: 원천이 오프셋 페이지네이션이고(커서 없음) 마지막 페이지를 명시하지 않아
 * "요청한 행 수보다 적게 오면 마지막"으로 추론해야 하는데, 이건 전부 벤더 응답 의미론이다. 호출자(수집 유스케이스)가
 * 순회하면 그 지식이 application으로 새고, 원천이 커서 방식으로 바뀌는 날 유스케이스가 함께 바뀐다.
 * 호출자가 정하는 것(무엇을 얼마나)은 {@link CatalogFetchCriteria}로 이미 넘어온다.
 */
@Component
@RequiredArgsConstructor
public class CultureCatalogReader implements ExhibitionCatalogClient {

	private static final Logger log = LoggerFactory.getLogger(CultureCatalogReader.class);

	private final CultureExhibitionClient client;
	private final CultureApiMapper mapper;
	/** 외부 호출 감사(append-only) — 문화포털은 무료라 billable=false. 저장은 REQUIRES_NEW라 호출자 트랜잭션과 생사를 같이하지 않는다. */
	private final ExternalApiCallLogRepository externalApiCallRepository;

	@Override
	public CatalogListData fetchAll(CatalogFetchCriteria criteria, CatalogPageStop pageStop) {
		if (!client.isConfigured()) {
			// 인증키 미설정: 외부 호출을 시도하지 않고 스킵한다(데모는 시드 데이터로 동작 — 04_전시_구현.md).
			log.info("CULTURE_API_KEY 미설정 — 동기화 스킵");
			return CatalogListData.none();
		}
		return toListData(fetchPages(criteria, pageStop));
	}

	/**
	 * 포트 계약을 이 클래스가 온전히 구현하기 위한 위임 — 상세는 페이징이 없어 단건 호출이 곧 결과다.
	 * 호출 감사도 여기서 남긴다(전송 클래스는 부르고 돌려주기만 한다).
	 * <p>
	 * 인증키 미설정이면 <b>호출 자체가 없으므로 감사 행도 남기지 않는다</b> — 유령 행이 "불렀는데 빈 응답"으로 읽힌다.
	 */
	@Override
	public Optional<DetailFetch> fetchDetailSnapshot(String externalId) {
		if (!client.isConfigured()) {
			return Optional.empty();
		}
		LocalDateTime calledAt = LocalDateTime.now();
		try {
			Optional<DetailFetch> fetched = client.fetchDetailSnapshot(externalId);
			// 호출은 정상인데 원천에 상세가 없다(NO_DATA) — 실패가 아니라 원천의 사실이다(재조회해도 소용없다).
			record(ExternalApiCallLog.free(ExternalApi.CULTURE_DETAIL, externalId,
					fetched.isPresent() ? ExternalApiOutcome.SUCCESS : ExternalApiOutcome.NO_DATA, calledAt));
			return fetched;
		} catch (RuntimeException e) {
			record(ExternalApiCallLog.free(ExternalApi.CULTURE_DETAIL, externalId,
					ExternalApiOutcome.FAILED, calledAt));
			throw e;
		}
	}

	/**
	 * 상한까지 페이지를 순회한다 — <b>두 가지 사유로 멈춘다.</b>
	 * <ol>
	 *   <li><b>덜 찬 페이지</b> = 마지막 페이지. 원천이 마지막을 명시하지 않으므로(hasNext·totalPages 없음)
	 *       받아 본 행 수로 추론할 수밖에 없다.</li>
	 *   <li><b>페이지 전량이 이미 아는 것</b>({@link CatalogPageStop}). 등록 역순이라 그 뒤로는 신규가 없다 —
	 *       더 부르면 이미 가진 것을 다시 받아 다시 처리하게 된다.</li>
	 * </ol>
	 * "어디까지 부를까"만 판단하고 내용은 보지 않는다 — 누적·판정은 {@link #toListData}의 몫이다.
	 * 중단 판정에 쓰는 id는 <b>필터 이전 응답 순서 그대로</b>다(적재 가능 여부는 여기의 관심사가 아니다).
	 */
	private List<CultureRealmListResponse> fetchPages(CatalogFetchCriteria criteria, CatalogPageStop pageStop) {
		List<CultureRealmListResponse> pages = new ArrayList<>();
		for (int pageNo = 1; pageNo <= criteria.maxCalls(); pageNo++) {
			CultureRealmListResponse page = fetchListPage(criteria, pageNo);
			pages.add(page);
			if (page.items().size() < criteria.pageSize()) {
				break; // 마지막 페이지
			}
			List<String> externalIds = page.items().stream()
					.map(CultureRealmListResponse.Item::seq)
					.filter(Objects::nonNull)
					.toList();
			if (!externalIds.isEmpty() && pageStop.allKnown(externalIds)) {
				log.debug("목록 순회 조기 종료 — {}페이지 전량이 기존 항목(등록 역순이라 이후는 신규 없음)", pageNo);
				break;
			}
		}
		return pages;
	}

	/**
	 * 목록 한 페이지 호출 + 감사 한 벌. 감사 키는 <b>페이지까지 찍는다</b>({@code realmCode=D000&page=3}) —
	 * 수집 1회가 3콜이면 3행이 남아야 "재시도 1건이 몇 콜을 태웠나"를 볼 수 있다.
	 */
	private CultureRealmListResponse fetchListPage(CatalogFetchCriteria criteria, int pageNo) {
		LocalDateTime calledAt = LocalDateTime.now();
		String requestKey = "realmCode=" + criteria.realm().code() + "&page=" + pageNo;
		try {
			CultureRealmListResponse page = client.fetchListPage(criteria, pageNo);
			record(ExternalApiCallLog.free(ExternalApi.CULTURE_LIST, requestKey, ExternalApiOutcome.SUCCESS, calledAt));
			return page;
		} catch (RuntimeException e) {
			record(ExternalApiCallLog.free(ExternalApi.CULTURE_LIST, requestKey, ExternalApiOutcome.FAILED, calledAt));
			throw e;
		}
	}

	/** 감사 기록은 부가 기능이다 — 여기서 실패해도 수집·적재를 깨지 않는다. */
	private void record(ExternalApiCallLog call) {
		try {
			externalApiCallRepository.save(call);
		} catch (RuntimeException e) {
			log.warn("외부 호출 감사 기록 실패(무시): {}", e.getMessage());
		}
	}

	/** 받아 온 페이지들을 수집 결과 한 벌로 접는다 — 적재 가능 필터 + 원천이 말한 총 건수. */
	private CatalogListData toListData(List<CultureRealmListResponse> pages) {
		List<CatalogExhibitionData> collected = pages.stream()
				.flatMap(page -> page.items().stream())
				.map(mapper::toCatalog)
				.filter(CatalogExhibitionData::isPersistable)
				.toList();
		return new CatalogListData(collected, totalCountOf(pages));
	}

	/**
	 * 원천이 말한 총 건수 — <b>가장 먼저 값을 준 페이지의 것으로 고정</b>한다(결측 페이지는 건너뛴다).
	 * 페이지마다 덮으면 중간 응답의 결측에 흔들린다.
	 */
	private Integer totalCountOf(List<CultureRealmListResponse> pages) {
		return pages.stream()
				.map(CultureRealmListResponse::totalCount)
				.filter(Objects::nonNull)
				.findFirst()
				.orElse(null);
	}
}
