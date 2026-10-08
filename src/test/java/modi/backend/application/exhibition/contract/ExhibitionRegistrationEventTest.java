package modi.backend.application.exhibition.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import modi.backend.application.exhibition.cache.ExhibitionRegisteredEvent;
import modi.backend.domain.exhibition.catalog.Exhibition;
import modi.backend.domain.exhibition.catalog.ExhibitionPlace;
import modi.backend.domain.exhibition.catalog.ExhibitionPlaceRepository;
import modi.backend.domain.exhibition.catalog.ExhibitionRegion;
import modi.backend.domain.exhibition.catalog.ExhibitionRepository;
import modi.backend.domain.exhibition.genre.GenreProvider;

/**
 * - 수집이 새 전시를 등록하면 등록 사실을 발행하는지 고정
 *   - 이 사실을 커밋 뒤 리스너가 받아 Redis 목록 캐시를 지움(수집 쪽 무효화 지점)
 *
 * - 이미 있던 전시로 응답한 경우엔 발행하지 않음
 *   - 목록이 바뀌지 않았는데 지우면 멀쩡한 캐시만 날아감
 */
@ExtendWith(MockitoExtension.class)
class ExhibitionRegistrationEventTest {

	@Mock
	private ExhibitionRepository exhibitionRepository;
	@Mock
	private ExhibitionPlaceRepository exhibitionPlaceRepository;
	@Mock
	private ApplicationEventPublisher eventPublisher;

	@InjectMocks
	private ExhibitionRegistrationFacade facade;

	private static ExhibitionRegistration 등록(String externalId) {
		return new ExhibitionRegistration(externalId, "전시", "전시장", ExhibitionRegion.SEOUL, "종로구", 127.0, 37.5,
				LocalDate.of(2026, 10, 1), LocalDate.of(2026, 12, 31), null, "poster", "detail", "기관",
				"무료", "설명", "img", null, null, null, "회화", GenreProvider.MOCK, "mock");
	}

	@Test
	@DisplayName("새 전시를 만들면 등록 사실을 발행한다")
	void register_신규_이벤트발행() {
		given(exhibitionRepository.findByExternalId("NEW-1")).willReturn(Optional.empty());
		ExhibitionPlace place = mock(ExhibitionPlace.class);
		given(place.getId()).willReturn(5L);
		given(exhibitionPlaceRepository.resolveOrCreate(anyString(), any(), any(), any(), any())).willReturn(place);
		Exhibition saved = mock(Exhibition.class);
		given(saved.getId()).willReturn(77L);
		given(exhibitionRepository.save(any(Exhibition.class))).willReturn(saved);

		facade.register(등록("NEW-1"), LocalDateTime.of(2026, 10, 9, 1, 0));

		ArgumentCaptor<ExhibitionRegisteredEvent> event = ArgumentCaptor.forClass(ExhibitionRegisteredEvent.class);
		verify(eventPublisher).publishEvent(event.capture());
		assertThat(event.getValue().exhibitionId()).isEqualTo(77L);
	}

	@Test
	@DisplayName("이미 있는 전시로 응답하면 발행하지 않는다 — 목록이 바뀌지 않았다")
	void register_기존_이벤트없음() {
		Exhibition existing = mock(Exhibition.class);
		given(existing.getId()).willReturn(10L);
		given(exhibitionRepository.findByExternalId("OLD-1")).willReturn(Optional.of(existing));

		facade.register(등록("OLD-1"), LocalDateTime.of(2026, 10, 9, 1, 0));

		verify(eventPublisher, never()).publishEvent(any(ExhibitionRegisteredEvent.class));
	}
}
