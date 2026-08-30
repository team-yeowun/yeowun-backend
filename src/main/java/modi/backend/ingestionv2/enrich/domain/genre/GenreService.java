package modi.backend.ingestionv2.enrich.domain.genre;

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
 * 장르 보강 스텝.
 *
 * <ul>
 *   <li>분류 입력은 상세 원장에서만 읽음. 수집 격벽의 목록 원장을 보지 않음</li>
 *   <li>폴백 판단은 어댑터 소유. 이 서비스는 결과에 담긴 사실만 기록</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class GenreService {

	private final EnrichmentRepository enrichmentRepository;
	private final GenreLedgerRepository genreLedgerRepository;
	private final DetailLedgerRepository detailLedgerRepository;
	private final GenreClassifier genreClassifier;
	private final OutboxAppender outboxAppender;

	/** 판정. 원장에 이미 기록이 있으면 외부 호출이 필요 없다. */
	@Transactional(readOnly = true)
	public boolean alreadyClassified(String vendorKey) {
		return genreLedgerRepository.existsByVendorKey(vendorKey);
	}

	/** 분류 입력 조회. 상세 원장이 없거나 분류할 글이 없으면 빈 값. */
	@Transactional(readOnly = true)
	public Optional<GenreInput> readInput(String vendorKey) {
		return detailLedgerRepository.findByVendorKey(vendorKey)
				.filter(CultureDetailSnapshot::hasClassifiableText)
				.map(snapshot -> new GenreInput(snapshot.getTitle(), snapshot.getContents()));
	}

	/** 외부 호출. 트랜잭션 밖에서 실행된다. */
	public GenreResult classify(GenreInput input) {
		return genreClassifier.classify(input.title(), input.description());
	}

	/** 반영. 원장 기록, 하위 전이, 완료 판정, 이벤트 적재를 한 트랜잭션으로 확정한다. */
	@Transactional
	public void apply(String vendorKey, GenreResult result) {
		Enrichment enrichment = enrichmentRepository.findForUpdate(vendorKey)
				.orElseThrow(() -> new CoreException(EnrichErrorCode.ENRICHMENT_NOT_FOUND));
		if (!genreLedgerRepository.existsByVendorKey(vendorKey)) {
			genreLedgerRepository.save(GenreSnapshot.create(vendorKey, result));
		}
		enrichment.onGenreDone(result.lastVendorName(), result.fallbackUsed());
		completeIfAllDone(vendorKey, enrichment);
	}

	/** 원장이 이미 있는 경우의 반영. 원장에 남은 공급자를 그대로 하위에 옮겨 적는다. */
	@Transactional
	public void applyAlreadyClassified(String vendorKey) {
		Enrichment enrichment = enrichmentRepository.findForUpdate(vendorKey)
				.orElseThrow(() -> new CoreException(EnrichErrorCode.ENRICHMENT_NOT_FOUND));
		String vendor = genreLedgerRepository.findByVendorKey(vendorKey)
				.map(snapshot -> snapshot.getVendor().name())
				.orElse(null);
		enrichment.onGenreDone(vendor, false);
		completeIfAllDone(vendorKey, enrichment);
	}

	/** 분류할 재료가 없는 경우. 다시 시도해도 결과가 달라지지 않으므로 즉시 확정한다. */
	@Transactional
	public void failWithoutInput(String vendorKey) {
		Enrichment enrichment = enrichmentRepository.findForUpdate(vendorKey)
				.orElseThrow(() -> new CoreException(EnrichErrorCode.ENRICHMENT_NOT_FOUND));
		enrichment.failWithoutRetry(EnrichStep.GENRE, "상세 원장에 분류할 글이 없습니다.");
		enrichmentRepository.save(enrichment);
	}

	private void completeIfAllDone(String vendorKey, Enrichment enrichment) {
		boolean completed = enrichment.completeIfAllDone();
		enrichmentRepository.save(enrichment);
		if (completed) {
			outboxAppender.append(IngestionEventType.ENRICHED, vendorKey);
		}
	}
}
