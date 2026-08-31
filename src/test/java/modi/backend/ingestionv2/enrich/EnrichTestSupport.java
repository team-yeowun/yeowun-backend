package modi.backend.ingestionv2.enrich;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;

import modi.backend.domain.exhibition.genre.GenreProvider;
import modi.backend.ingestionv2.IngestionTestSupport;
import modi.backend.ingestionv2.common.event.IngestionEventType;
import modi.backend.ingestionv2.common.outbox.Outbox;
import modi.backend.ingestionv2.enrich.domain.EnrichFacade;
import modi.backend.ingestionv2.enrich.domain.EnrichStep;
import modi.backend.ingestionv2.enrich.domain.Enrichment;
import modi.backend.ingestionv2.enrich.domain.StepStatus;
import modi.backend.ingestionv2.enrich.domain.detail.DetailData;
import modi.backend.ingestionv2.enrich.domain.genre.GenreResult;
import modi.backend.ingestionv2.enrich.domain.hours.PlaceData;
import modi.backend.ingestionv2.enrich.infra.DetailLedgerJpaRepository;
import modi.backend.ingestionv2.enrich.infra.EnrichmentJpaRepository;
import modi.backend.ingestionv2.enrich.infra.GenreLedgerJpaRepository;
import modi.backend.ingestionv2.enrich.infra.PlaceLedgerJpaRepository;

/** 보강 케이스가 공유하는 픽스처와 관측 창구. 공통 토대를 상속만 하고 새 토대를 만들지 않는다. */
abstract class EnrichTestSupport extends IngestionTestSupport {

	@Autowired protected EnrichFacade enrichFacade;
	@Autowired protected EnrichmentJpaRepository enrichmentRepository;
	@Autowired protected DetailLedgerJpaRepository detailLedgerRepository;
	@Autowired protected GenreLedgerJpaRepository genreLedgerRepository;
	@Autowired protected PlaceLedgerJpaRepository placeLedgerRepository;

	protected static DetailData detailData() {
		return new DetailData("여운 기획전", "2026-08-01", "2026-12-31", "여운 미술관", "미술", "서울", "종로구",
				"126.97", "37.57", "무료", "전시 설명 원문", "https://exh.example/1001", "02-000-0000",
				"https://img.example/detail.jpg", false);
	}

	protected static GenreResult genreResult() {
		return GenreResult.classified("현대미술", GenreProvider.GEMINI, "gemini-2.5-flash", List.of());
	}

	protected static PlaceData placeData() {
		return new PlaceData("place-1", "여운 미술관", "서울 종로구 1-1",
				"{\"weekdayDescriptions\":[\"월요일: 휴무\",\"화요일: 10:00~18:00\"]}", false);
	}

	/** 보강을 열고 상세까지 끝낸 상태를 만든다. 장르와 개장 시간이 READY 로 열린다. */
	protected void openThroughDetail() {
		handle(IngestionEventType.COLLECTED, vendorKey);
		handle(IngestionEventType.DETAIL_READY, vendorKey);
	}

	protected Enrichment enrichment() {
		return enrichmentRepository.findByVendorKey(vendorKey).orElseThrow();
	}

	protected StepStatus statusOf(EnrichStep step) {
		return enrichment().statusOf(step);
	}

	protected List<Outbox> outboxOf(IngestionEventType type) {
		return outboxRepository.findAll().stream()
				.filter(outbox -> outbox.getEventType() == type)
				.toList();
	}
}
