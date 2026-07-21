package modi.backend.ingestion.infra.culture;

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
import modi.backend.ingestion.domain.port.ExhibitionCatalogClient;

/**
 * {@link ExhibitionCatalogClient} 어댑터 — 한눈에보는문화정보(15138937)의 <b>페이징 모델</b>을 아는 유일한 곳이다(DIP).
 * <p>
 * 단건 호출(요청선 조립·전송·감사·예외 변환)은 {@link CultureExhibitionClient}에 위임하고, 이 클래스는
 * <b>"어디까지 부를지"와 "받아 온 것을 어떻게 접을지"</b>만 판단한다. 두 클래스의 변경 이유가 갈린다 —
 * 페이징 방식이 바뀌면 여기만, 요청선·감사가 바뀌면 저기만 바뀐다.
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

	@Override
	public CatalogListData fetchAll(CatalogFetchCriteria criteria) {
		if (!client.isConfigured()) {
			// 인증키 미설정: 외부 호출을 시도하지 않고 스킵한다(데모는 시드 데이터로 동작 — 04_전시_구현.md).
			log.info("CULTURE_API_KEY 미설정 — 동기화 스킵");
			return CatalogListData.none();
		}
		return toListData(fetchPages(criteria));
	}

	/**
	 * 포트 계약을 이 클래스가 온전히 구현하기 위한 위임 — 상세는 페이징이 없어 단건 호출이 곧 결과다.
	 */
	@Override
	public Optional<DetailFetch> fetchDetailSnapshot(String externalId) {
		return client.fetchDetailSnapshot(externalId);
	}

	/**
	 * 상한까지 페이지를 순회한다 — <b>덜 찬 페이지를 만나면 거기서 멈춘다.</b>
	 * 원천이 마지막 페이지를 명시하지 않으므로(hasNext·totalPages 없음) 받아 본 행 수로 추론할 수밖에 없다.
	 * <p>
	 * "어디까지 부를까"만 판단하고 내용은 보지 않는다 — 누적·판정은 {@link #toListData}의 몫이다.
	 */
	private List<CultureRealmListResponse> fetchPages(CatalogFetchCriteria criteria) {
		List<CultureRealmListResponse> pages = new ArrayList<>();
		for (int pageNo = 1; pageNo <= criteria.maxCalls(); pageNo++) {
			CultureRealmListResponse page = client.fetchListPage(criteria, pageNo);
			pages.add(page);
			if (page.items().size() < criteria.pageSize()) {
				break; // 마지막 페이지
			}
		}
		return pages;
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
