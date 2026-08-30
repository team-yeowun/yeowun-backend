package modi.backend.ingestionv2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import modi.backend.domain.exhibition.genre.GenreProvider;
import modi.backend.ingestionv2.collect.domain.CatalogItem;
import modi.backend.ingestionv2.collect.domain.CollectCriteria;
import modi.backend.ingestionv2.collect.domain.CollectFacade;
import modi.backend.ingestionv2.common.IngestionClock;
import modi.backend.ingestionv2.common.event.IngestionEventType;
import modi.backend.ingestionv2.common.outbox.Outbox;
import modi.backend.ingestionv2.common.outbox.OutboxStatus;
import modi.backend.ingestionv2.common.queue.IngestionStream;
import modi.backend.ingestionv2.enrich.domain.detail.DetailData;
import modi.backend.ingestionv2.enrich.domain.genre.GenreResult;
import modi.backend.ingestionv2.enrich.domain.hours.PlaceData;

/**
 * 네 격벽을 가로지르는 종단 흐름.
 *
 * <ul>
 *   <li>격벽 어느 쪽에도 속하지 않으므로 슬라이스 루트에 둔다 - 한 격벽의 스위트가 다른 격벽을 import 하지 않게</li>
 *   <li>벤더 응답 넷만 세우고 나머지는 전부 실물. 배달도 실제 Redis 스트림을 지난다</li>
 *   <li>기다리지 않는다. 발송기와 소비기를 테스트가 한 틱씩 민다</li>
 * </ul>
 */
@DisplayName("파이프라인 종단 흐름")
class IngestionPipelineFlowTest extends IngestionTestSupport {

	@Autowired private CollectFacade collectFacade;

	@Test
	@DisplayName("목록 한 건이 수집에서 시작해 코어 등록까지 도달한다")
	void 목록_한_건이_코어_등록까지_도달한다() {
		// given 벤더 응답 넷을 세운다
		String placeName = "여운 미술관 " + vendorKey;
		given(catalogClient.fetchCatalog()).willReturn(List.of(new CatalogItem(
				vendorKey, "여운 기획전", "2026-08-01", "2026-12-31", placeName, "미술", "서울", "종로구",
				"https://img.example/thumb.jpg", "126.97", "37.57", "전시", "https://exh.example/1001")));
		given(detailClient.fetchDetail(vendorKey)).willReturn(new DetailData(
				"여운 기획전", "2026-08-01", "2026-12-31", placeName, "미술", "서울", "종로구",
				"126.97", "37.57", "무료", "전시 설명 원문", "https://exh.example/1001", "02-000-0000",
				"https://img.example/detail.jpg", false));
		given(genreClassifier.classify(any(), any()))
				.willReturn(GenreResult.classified("회화", GenreProvider.GEMINI, "gemini-test", List.of()));
		given(placeHoursClient.fetchPlace(any(), any())).willReturn(new PlaceData(
				"place-1", placeName, "서울 종로구 1-1",
				"{\"weekdayDescriptions\":[\"월요일: 휴관\"]}", false));

		// when 회차를 한 번 돌리고 큐가 빌 때까지 민다
		collectFacade.collect(CollectCriteria.Batch.of(IngestionClock.today()));
		drainAll();

		// then 일곱 사실이 순서대로 적재되고 전부 발행 완료다
		List<Outbox> outboxes = outboxRepository.findAll();
		assertThat(outboxes).extracting(Outbox::getEventType)
				.containsExactlyInAnyOrder(
						IngestionEventType.COLLECTED,
						IngestionEventType.DETAIL_READY,
						IngestionEventType.GENRE_READY,
						IngestionEventType.HOURS_READY,
						IngestionEventType.ENRICHED,
						IngestionEventType.INSPECTED,
						IngestionEventType.STAGED);
		assertThat(outboxes).allMatch(outbox -> outbox.getStatus() == OutboxStatus.SENT);

		// then 코어에 전시가 남고 격리는 비어 있다
		assertThat(jdbcTemplate.queryForObject(
				"select count(*) from exhibitions where external_id = ?", Integer.class, vendorKey)).isEqualTo(1);
		assertThat(deadLetterRepository.findAll()).isEmpty();

		// then 미처리 목록이 비어 있다(이 목록이 곧 "아직 끝나지 않은 일"의 사본이다)
		for (IngestionStream stream : IngestionStream.values()) {
			assertThat(pendingOf(stream).isEmpty()).isTrue();
		}
	}
}
