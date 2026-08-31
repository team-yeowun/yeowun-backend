package modi.backend.ingestionv2.enrich.domain.hours;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import modi.backend.ingestionv2.common.event.IngestionEventType;
import modi.backend.ingestionv2.common.outbox.OutboxAppender;
import modi.backend.ingestionv2.enrich.domain.EnrichErrorCode;
import modi.backend.ingestionv2.enrich.domain.EnrichStep;
import modi.backend.ingestionv2.enrich.domain.Enrichment;
import modi.backend.ingestionv2.enrich.domain.EnrichmentRepository;
import modi.backend.ingestionv2.enrich.domain.detail.CultureDetailSnapshot;
import modi.backend.ingestionv2.enrich.domain.detail.DetailLedgerRepository;
import modi.backend.support.error.CoreException;

/**
 * 개장 시간 보강 스텝.
 *
 * <ul>
 *   <li>조회 입력은 상세 원장의 장소 표기 하나. 좌표나 목록 원장을 참조하지 않음</li>
 *   <li>장소를 못 찾은 경우도 원장 행으로 남김. 조회했다는 사실이 완비의 근거</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class HoursService {

	private final EnrichmentRepository enrichmentRepository;
	private final PlaceLedgerRepository placeLedgerRepository;
	private final DetailLedgerRepository detailLedgerRepository;
	private final PlaceHoursClient placeHoursClient;
	private final OutboxAppender outboxAppender;

	/** 판정. 원장에 이미 기록이 있으면 외부 호출이 필요 없다. */
	@Transactional(readOnly = true)
	public boolean alreadyFetched(String vendorKey) {
		return placeLedgerRepository.existsByVendorKey(vendorKey);
	}

	/** 조회 입력 조회. 상세 원장에 장소 표기가 없으면 빈 값. */
	@Transactional(readOnly = true)
	public Optional<PlaceInput> readInput(String vendorKey) {
		return detailLedgerRepository.findByVendorKey(vendorKey)
				.filter(CultureDetailSnapshot::hasSearchablePlace)
				.map(snapshot -> new PlaceInput(snapshot.getPlace(), address(snapshot)));
	}

	/** 외부 호출. 트랜잭션 밖에서 실행된다. */
	public PlaceData fetch(PlaceInput input) {
		return placeHoursClient.fetchPlace(input.placeName(), input.placeAddress());
	}

	/** 반영. 원장 기록, 하위 전이, 완료 판정, 이벤트 적재를 한 트랜잭션으로 확정한다. */
	@Transactional
	public void apply(String vendorKey, PlaceData data) {
		Enrichment enrichment = enrichmentRepository.findForUpdate(vendorKey)
				.orElseThrow(() -> new CoreException(EnrichErrorCode.ENRICHMENT_NOT_FOUND));
		if (!placeLedgerRepository.existsByVendorKey(vendorKey)) {
			placeLedgerRepository.save(GooglePlaceSnapshot.create(vendorKey, data));
		}
		enrichment.onHoursDone();
		completeIfAllDone(vendorKey, enrichment);
	}

	/** 원장이 이미 있는 경우의 반영. 외부 호출 없이 상태 전이와 완료 판정만 수행한다. */
	@Transactional
	public void applyAlreadyFetched(String vendorKey) {
		Enrichment enrichment = enrichmentRepository.findForUpdate(vendorKey)
				.orElseThrow(() -> new CoreException(EnrichErrorCode.ENRICHMENT_NOT_FOUND));
		enrichment.onHoursDone();
		completeIfAllDone(vendorKey, enrichment);
	}

	/** 조회할 장소 표기가 없는 경우. 다시 시도해도 결과가 달라지지 않으므로 즉시 확정한다. */
	@Transactional
	public void failWithoutInput(String vendorKey) {
		Enrichment enrichment = enrichmentRepository.findForUpdate(vendorKey)
				.orElseThrow(() -> new CoreException(EnrichErrorCode.ENRICHMENT_NOT_FOUND));
		enrichment.failWithoutRetry(EnrichStep.HOURS, "상세 원장에 조회할 장소 표기가 없습니다.");
		enrichmentRepository.save(enrichment);
	}

	private void completeIfAllDone(String vendorKey, Enrichment enrichment) {
		boolean completed = enrichment.completeIfAllDone();
		enrichmentRepository.save(enrichment);
		if (completed) {
			outboxAppender.append(IngestionEventType.ENRICHED, vendorKey);
		}
	}

	/** 조회 주소. 상세의 장소 표기를 그대로 쓰되 지역과 시군구가 있으면 앞에 덧붙인다. */
	private static String address(CultureDetailSnapshot snapshot) {
		StringBuilder builder = new StringBuilder();
		if (snapshot.getArea() != null && !snapshot.getArea().isBlank()) {
			builder.append(snapshot.getArea()).append(' ');
		}
		if (snapshot.getSigungu() != null && !snapshot.getSigungu().isBlank()) {
			builder.append(snapshot.getSigungu()).append(' ');
		}
		return builder.append(snapshot.getPlace()).toString();
	}
}
