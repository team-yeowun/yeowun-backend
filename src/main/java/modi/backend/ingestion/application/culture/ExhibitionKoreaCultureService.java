package modi.backend.ingestion.application.culture;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import modi.backend.application.audit.ExternalApiCallLogRecorder;
import modi.backend.domain.audit.ApiCallSource;
import modi.backend.domain.audit.ExternalApi;
import modi.backend.domain.audit.ExternalApiCallLog;
import modi.backend.domain.audit.ExternalApiOutcome;
import modi.backend.domain.exhibition.genre.GenreClassification;
import modi.backend.ingestion.domain.data.CatalogExhibitionData;
import modi.backend.ingestion.domain.data.CatalogFetchCriteria;
import modi.backend.ingestion.domain.data.CatalogPage;
import modi.backend.ingestion.domain.data.CultureDetailPayload;
import modi.backend.ingestion.domain.port.ExhibitionCatalogClient;
import modi.backend.ingestion.infra.culture.CultureFieldCodec;
import modi.backend.ingestion.infra.snapshot.CultureDetailSnapshotJpaRepository;
import modi.backend.ingestion.infra.snapshot.CultureListSnapshotJpaRepository;
import modi.backend.ingestion.properties.CatalogFetchProperties;

/**
 * 한눈에보는문화정보(문화포털) 축의 서비스 — <b>목록/상세 호출(tx 밖)·CULTURE_* 콜 감사·자기 원장 읽기</b>를
 * 맡는다. 원장 <b>쓰기</b>는 여기 없다(설계 §1-2 원장 합류) — 목록·상세 스냅샷 upsert는 {@code SnapshotLedger}가
 * 진행 상태 반영 트랜잭션에서 한다(구 archive 메서드의 best-effort 삼킴 폐기).
 *
 * <p>다음 스텝 지식·게이트 판단은 하지 않는다 — 그건 {@code ExhibitionIngestionOrchestrator}(순서 매핑)와
 * {@code ExhibitionProgressService}(게이트)의 몫이다.
 *
 * <p><b>콜 로그는 콜 단위다</b>: 목록은 페이지 순회라 콜마다 한 행, 상세는 1콜=1행. 호출 직후
 * {@link ExternalApiCallLogRecorder}(코어 공용 감사, source=INGESTION)로 즉시 남긴다.
 */
@Service
@RequiredArgsConstructor
public class ExhibitionKoreaCultureService {

	private static final Logger log = LoggerFactory.getLogger(ExhibitionKoreaCultureService.class);

	private final ExhibitionCatalogClient catalogClient;
	/** 외부 호출 감사(공용) — REQUIRES_NEW + 삼킴은 Recorder가 진다. */
	private final ExternalApiCallLogRecorder callLogRecorder;
	private final CultureListSnapshotJpaRepository cultureListSnapshotRepository;
	private final CultureDetailSnapshotJpaRepository cultureDetailSnapshotRepository;
	/** 수집 요청 정책(무엇을 얼마나) — 이 축이 소유해 {@link CatalogFetchCriteria}로 조립, 포트 인자로 내려보낸다. */
	private final CatalogFetchProperties fetchProperties;

	/**
	 * 목록을 <b>페이지 단위로 순회</b>한다(tx 밖) — 콜 하나하나가 감사의 단위이고, 조기 종료 판정의 단위다.
	 * 두 사유로 멈춘다: ① 덜 찬 페이지(= 마지막) ② 페이지 전량이 이미 아는 것(등록 역순이라 그 뒤로는 신규가
	 * 없다 — 판정은 필터 이전 식별자로). 인증키 미설정이면 호출 없이 빈 결과.
	 */
	public List<CatalogExhibitionData> fetchPages(LocalDateTime syncedAt) {
		if (!catalogClient.isConfigured()) {
			log.info("CULTURE_API_KEY 미설정 — 동기화 스킵");
			return List.of();
		}
		CatalogFetchCriteria criteria = fetchProperties.toCriteria();
		List<CatalogExhibitionData> collected = new ArrayList<>();
		for (int pageNo = 1; pageNo <= criteria.maxCalls(); pageNo++) {
			CatalogPage page = fetchPage(criteria, pageNo, syncedAt);
			page.items().stream().filter(CatalogExhibitionData::isPersistable).forEach(collected::add);
			if (!page.isFull(criteria.pageSize())) {
				break; // 마지막 페이지
			}
			List<String> externalIds = page.externalIds();
			if (!externalIds.isEmpty() && allSnapshotted(externalIds)) {
				log.debug("목록 순회 조기 종료 — {}페이지 전량이 기존 항목(등록 역순이라 이후는 신규 없음)", pageNo);
				break;
			}
		}
		return collected;
	}

	/** 한 콜 = 감사 한 행. 실패도 남기고 그대로 전파한다(수집 자체는 실패로 끝나야 한다). */
	private CatalogPage fetchPage(CatalogFetchCriteria criteria, int pageNo, LocalDateTime calledAt) {
		String requestKey = "realmCode=" + criteria.realm().code() + "&page=" + pageNo;
		try {
			CatalogPage page = catalogClient.fetchPage(criteria, pageNo);
			callLogRecorder.record(ExternalApiCallLog.of(ApiCallSource.INGESTION, ExternalApi.CULTURE_LIST,
					requestKey, ExternalApiOutcome.SUCCESS, calledAt));
			return page;
		} catch (RuntimeException e) {
			callLogRecorder.record(ExternalApiCallLog.of(ApiCallSource.INGESTION, ExternalApi.CULTURE_LIST,
					requestKey, ExternalApiOutcome.FAILED, calledAt));
			throw e;
		}
	}

	/**
	 * 단건 상세(detail2)를 조회한다(tx 밖) — 호출 1건 = 감사 1행({@code CULTURE_DETAIL}, requestKey=external_id).
	 * 원문 보관은 여기서 하지 않는다 — 원장화(설계 §1-2)로 상세 반영 트랜잭션에 합류한다({@code SnapshotLedger}).
	 * 실패는 감사만 남기고 그대로 전파한다(메시지 수명주기는 호출부가 잇는다).
	 */
	public CultureDetailPayload fetchDetail(String externalId) {
		LocalDateTime calledAt = LocalDateTime.now();
		CultureDetailPayload payload;
		try {
			payload = catalogClient.fetchDetail(externalId);
		} catch (RuntimeException e) {
			callLogRecorder.record(ExternalApiCallLog.of(ApiCallSource.INGESTION, ExternalApi.CULTURE_DETAIL,
					externalId, ExternalApiOutcome.FAILED, calledAt));
			throw e;
		}
		callLogRecorder.record(ExternalApiCallLog.of(ApiCallSource.INGESTION, ExternalApi.CULTURE_DETAIL,
				externalId, ExternalApiOutcome.SUCCESS, calledAt));
		return payload;
	}

	/**
	 * 이 식별자들이 <b>전부</b> 이미 스냅샷에 있는가 — 목록 순회 조기 종료 판정. 스냅샷이 가장 넓은 집합이다
	 * (승격 전 진행 항목도, 기간 불량 스킵 항목도 행이 있다). 조회는 페이지당 1회({@code IN})다.
	 */
	@Transactional(readOnly = true)
	public boolean allSnapshotted(List<String> externalIds) {
		if (externalIds.isEmpty()) {
			return false;
		}
		Set<String> distinct = new HashSet<>(externalIds);
		return cultureListSnapshotRepository.countByExternalIdIn(distinct) == distinct.size();
	}

	/** 목록 원장 → 수집 데이터 복원 — 전시장 축(PLACE_STAGED 소비)의 시드 소스(자기 축 원장 읽기). */
	@Transactional(readOnly = true)
	public Optional<CatalogExhibitionData> catalogDataOf(String externalId) {
		return cultureListSnapshotRepository.findByExternalId(externalId)
				.map(snapshot -> snapshot.toCatalogData());
	}

	/**
	 * 장르 분류 입력 조립 — 목록·상세 원장에서 읽는다(구 draft 컬럼 소멸로 이관, 설계 §3-1). 상세는 무상세면
	 * 없을 수 있다(설명 null 조립). 목록 원장이 없으면 empty — 분류할 대상이 아니다(마커⇒원장 불변식상 비정상,
	 * 스텝은 성공 마감하고 재sync가 치유한다).
	 */
	@Transactional(readOnly = true)
	public Optional<GenreClassification> genreInputOf(String externalId) {
		return cultureListSnapshotRepository.findByExternalId(externalId).map(list -> {
			CatalogExhibitionData data = list.toCatalogData();
			String description = cultureDetailSnapshotRepository.findByExternalId(externalId)
					.map(d -> CultureFieldCodec.decodeDescription(CultureFieldCodec.blankToNull(d.getContents())))
					.orElse(null);
			return new GenreClassification(data.title(),
					data.category() == null ? null : data.category().name(),
					description, data.place(), null, data.realmName());
		});
	}
}
