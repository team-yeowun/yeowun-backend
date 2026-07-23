package modi.backend.ingestion.application.culture;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import modi.backend.ingestion.application.audit.ExternalApiCallLogRecorder;
import modi.backend.ingestion.domain.audit.ExternalApi;
import modi.backend.ingestion.domain.audit.ExternalApiCallLog;
import modi.backend.ingestion.domain.audit.ExternalApiOutcome;
import modi.backend.ingestion.domain.data.CatalogExhibitionData;
import modi.backend.ingestion.domain.data.CatalogFetchCriteria;
import modi.backend.ingestion.domain.data.CatalogPage;
import modi.backend.ingestion.domain.data.CultureDetailPayload;
import modi.backend.ingestion.domain.snapshot.CultureDetailSnapshot;
import modi.backend.ingestion.domain.snapshot.CultureListSnapshot;
import modi.backend.ingestion.domain.port.ExhibitionCatalogClient;
import modi.backend.ingestion.infra.snapshot.CultureDetailSnapshotJpaRepository;
import modi.backend.ingestion.infra.snapshot.CultureListSnapshotJpaRepository;
import modi.backend.ingestion.properties.CatalogFetchProperties;

/**
 * 한눈에보는문화정보(문화포털) 축의 서비스 — <b>목록/상세 호출(tx 밖)·CULTURE_* 콜 로그·자기 소유물 반영 tx</b>를
 * 맡는다(설계 §5). 다음 스텝 지식·draft 게이트 판단은 하지 않는다 — 그건 {@code ExhibitionIngestionOrchestrator}
 * (순서 매핑)와 {@code ExhibitionDraftService}(게이트)의 몫이다.
 *
 * <p><b>콜 로그는 콜 단위다</b>: 목록은 페이지 순회라 콜마다 한 행(순회를 아는 호출부가 감사의 단위를 안다),
 * 상세는 1콜=1행. 호출 직후 {@link ExternalApiCallLogRecorder}로 즉시 남긴다 — 재설계 전 코드는 상세 호출
 * 감사가 누락돼 있었다(설계 체크리스트 — 호출하면 반드시 record).
 *
 * <p><b>상세 스냅샷은 호출 직후 best-effort</b>: 벤더 원문은 독립 사실이라 이후 draft/코어 반영이 실패해도
 * 남는 게 맞다(행위 변경 — 예전엔 반영 트랜잭션 안에서만 보관돼 반영 실패 시 원문도 사라졌다).
 */
@Service
@RequiredArgsConstructor
public class ExhibitionKoreaCultureService {

	private static final Logger log = LoggerFactory.getLogger(ExhibitionKoreaCultureService.class);

	private final ExhibitionCatalogClient catalogClient;
	/** 외부 호출 감사 — REQUIRES_NEW + 삼킴은 Recorder가 진다. */
	private final ExternalApiCallLogRecorder callLogRecorder;
	private final CultureListSnapshotJpaRepository cultureListSnapshotRepository;
	private final CultureDetailSnapshotJpaRepository cultureDetailSnapshotRepository;
	/** 수집 요청 정책(무엇을 얼마나) — 이 축이 소유해 {@link CatalogFetchCriteria}로 조립, 포트 인자로 내려보낸다. */
	private final CatalogFetchProperties fetchProperties;

	/** 순회 결과 한 벌 — 적재 가능 항목과 원천이 말한 총 건수. */
	public record Fetched(List<CatalogExhibitionData> items, Integer totalCount) {
	}

	/**
	 * 목록을 <b>페이지 단위로 순회</b>한다(tx 밖) — 콜 하나하나가 감사의 단위이고, 조기 종료 판정의 단위다.
	 * 요청 조건은 이 축의 설정({@link CatalogFetchProperties})에서 조립한다 — 어댑터가 직접 읽지 않는다.
	 * <p>
	 * 두 사유로 멈춘다: ① 덜 찬 페이지(= 마지막 — 원천이 마지막을 명시하지 않는다) ② 페이지 전량이 이미 아는 것
	 * (등록 역순이라 그 뒤로는 신규가 없다). ②의 판정은 <b>필터 이전 식별자</b>로 한다 — 걸러진 불량 행을 빼면
	 * "전량 known"이 영영 성립하지 않아 조기 종료가 죽는다. 인증키 미설정이면 호출 없이 빈 결과.
	 */
	public Fetched fetchPages(LocalDateTime syncedAt) {
		if (!catalogClient.isConfigured()) {
			log.info("CULTURE_API_KEY 미설정 — 동기화 스킵");
			return new Fetched(List.of(), null);
		}
		CatalogFetchCriteria criteria = fetchProperties.toCriteria();
		List<CatalogExhibitionData> collected = new ArrayList<>();
		Integer totalCount = null;
		for (int pageNo = 1; pageNo <= criteria.maxCalls(); pageNo++) {
			CatalogPage page = fetchPage(criteria, pageNo, syncedAt);
			if (totalCount == null) {
				totalCount = page.totalCount(); // 첫 응답 값으로 고정(페이지마다 덮으면 중간 결측에 흔들린다)
			}
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
		return new Fetched(collected, totalCount);
	}

	/** 한 콜 = 감사 한 행. 실패도 남기고 그대로 전파한다(수집 자체는 실패로 끝나야 한다). */
	private CatalogPage fetchPage(CatalogFetchCriteria criteria, int pageNo, LocalDateTime calledAt) {
		String requestKey = "realmCode=" + criteria.realm().code() + "&page=" + pageNo;
		try {
			CatalogPage page = catalogClient.fetchPage(criteria, pageNo);
			callLogRecorder.record(ExternalApiCallLog.of(ExternalApi.CULTURE_LIST, requestKey,
					ExternalApiOutcome.SUCCESS, calledAt));
			return page;
		} catch (RuntimeException e) {
			callLogRecorder.record(ExternalApiCallLog.of(ExternalApi.CULTURE_LIST, requestKey,
					ExternalApiOutcome.FAILED, calledAt));
			throw e;
		}
	}

	/**
	 * 단건 상세(detail2)를 조회한다(tx 밖) — 호출 1건 = 감사 1행({@code CULTURE_DETAIL}, requestKey=external_id).
	 * 성공하면 응답 원문을 즉시 best-effort로 스냅샷에 보관한다(벤더 원문은 독립 사실 — 이후 반영이 실패해도 남는다).
	 * 실패는 감사만 남기고 그대로 전파한다(메시지 수명주기는 호출부가 잇는다).
	 */
	public CultureDetailPayload fetchDetail(String externalId) {
		LocalDateTime calledAt = LocalDateTime.now();
		CultureDetailPayload payload;
		try {
			payload = catalogClient.fetchDetail(externalId);
		} catch (RuntimeException e) {
			callLogRecorder.record(ExternalApiCallLog.of(ExternalApi.CULTURE_DETAIL, externalId,
					ExternalApiOutcome.FAILED, calledAt));
			throw e;
		}
		callLogRecorder.record(ExternalApiCallLog.of(ExternalApi.CULTURE_DETAIL, externalId,
				ExternalApiOutcome.SUCCESS, calledAt));
		archiveDetailSnapshot(externalId, payload);
		return payload;
	}

	/**
	 * 이 식별자들이 <b>전부</b> 이미 스냅샷에 있는가 — 목록 순회 조기 종료 판정.
	 *
	 * <p><b>왜 {@code exhibitions}가 아니라 스냅샷 기준인가</b>: 스냅샷이 가장 넓은 집합이다. 승격 전 draft 단계
	 * 항목도, 기간 불량으로 도메인이 적재하지 않은 항목도 여기엔 행이 있다. {@code exhibitions}로 판정하면 그것들이
	 * 매 동기화마다 "모르는 것"으로 잡혀 조기 종료가 사실상 걸리지 않는다.
	 *
	 * <p>조회는 <b>페이지당 1회</b>({@code IN})다 — 건당 물으면 아끼려던 쿼리를 판정에서 도로 쓴다.
	 */
	@Transactional(readOnly = true)
	public boolean allSnapshotted(List<String> externalIds) {
		if (externalIds.isEmpty()) {
			return false;
		}
		Set<String> distinct = new HashSet<>(externalIds);
		return cultureListSnapshotRepository.countByExternalIdIn(distinct) == distinct.size();
	}

	/** 벤더 목록 스냅샷 upsert(필드 적재 — ADR-13). 부가 기록이라 실패해도 동기화를 깨지 않는다(이 행 스냅샷만 누락). */
	public void archiveListSnapshot(CatalogExhibitionData data, LocalDateTime syncedAt) {
		try {
			cultureListSnapshotRepository.findByExternalId(data.externalId())
					.ifPresentOrElse(row -> {
						row.seenAgain(data, syncedAt);
						cultureListSnapshotRepository.save(row);
					}, () -> cultureListSnapshotRepository.save(
							CultureListSnapshot.first(data, syncedAt)));
		} catch (RuntimeException e) {
			log.warn("목록 스냅샷 적재 실패(externalId={}, 동기화는 계속): {}", data.externalId(), e.getMessage());
		}
	}

	/** 상세 스냅샷 upsert(응답 원문 그대로 — ADR-13). 부가 기록이라 실패해도 반영을 깨지 않는다. */
	private void archiveDetailSnapshot(String externalId, CultureDetailPayload payload) {
		try {
			cultureDetailSnapshotRepository.findByExternalId(externalId)
					.ifPresentOrElse(row -> {
						row.refresh(payload);
						cultureDetailSnapshotRepository.save(row);
					}, () -> cultureDetailSnapshotRepository.save(
							CultureDetailSnapshot.first(externalId, payload)));
		} catch (RuntimeException e) {
			log.warn("상세 스냅샷 적재 실패(externalId={}, 반영은 계속): {}", externalId, e.getMessage());
		}
	}
}
