package modi.backend.application.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import modi.backend.application.exhibition.cache.ExhibitionAdminUpdatedEvent;
import modi.backend.domain.exhibition.catalog.Exhibition;
import modi.backend.domain.exhibition.catalog.ExhibitionPlaceRepository;
import modi.backend.domain.exhibition.catalog.ExhibitionRepository;
import modi.backend.infra.exhibition.catalog.ExhibitionHistoryJpaRepository;

/**
 * - 관리자 수정이 무효화 이벤트를 내는지 고정
 *
 * - 값이 그대로면 이벤트도 나가지 않아야 함
 *   - 조기 반환보다 뒤에 발행해야 이 성질이 유지됨
 *   - 앞으로 옮기면 아무것도 안 바뀐 수정 요청마다 전 서버 캐시가 날아감
 */
@ExtendWith(MockitoExtension.class)
class AdminExhibitionEditEventTest {

	@Mock
	private ExhibitionRepository exhibitionRepository;
	@Mock
	private ExhibitionPlaceRepository exhibitionPlaceRepository;
	@Mock
	private ExhibitionHistoryJpaRepository exhibitionHistoryRepository;
	@Mock
	private ApplicationEventPublisher eventPublisher;

	@InjectMocks
	private AdminExhibitionFacade facade;

	private static Exhibition 전시(String title) {
		return Exhibition.createCatalog("S1", title, 5L, null, null, null, null, null, null, "기관");
	}

	@Test
	@DisplayName("실제로 바뀌면 무효화 이벤트를 발행한다")
	void edit_변경있음_이벤트발행() {
		given(exhibitionRepository.findById(1L)).willReturn(Optional.of(전시("옛 제목")));

		AdminExhibitionResult.Edited result = facade.editExhibition(1L, "새 제목", null, null, null);

		assertThat(result.changedFields()).isEqualTo(1);
		ArgumentCaptor<ExhibitionAdminUpdatedEvent> event =
				ArgumentCaptor.forClass(ExhibitionAdminUpdatedEvent.class);
		verify(eventPublisher).publishEvent(event.capture());
		assertThat(event.getValue().exhibitionId()).isEqualTo(1L);
	}

	@Test
	@DisplayName("값이 그대로면 이벤트도 나가지 않는다 — 멀쩡한 캐시를 헛되이 날리지 않는다")
	void edit_변경없음_이벤트없음() {
		given(exhibitionRepository.findById(1L)).willReturn(Optional.of(전시("같은 제목")));

		AdminExhibitionResult.Edited result = facade.editExhibition(1L, "같은 제목", null, null, null);

		assertThat(result.changedFields()).isZero();
		verify(eventPublisher, never()).publishEvent(any(ExhibitionAdminUpdatedEvent.class));
	}

	@Test
	@DisplayName("저장·이력 적재가 끝난 뒤에 발행한다")
	void edit_저장후_발행() {
		given(exhibitionRepository.findById(1L)).willReturn(Optional.of(전시("옛 제목")));

		facade.editExhibition(1L, "새 제목", null, null, null);

		InOrder order = inOrder(exhibitionRepository, exhibitionHistoryRepository, eventPublisher);
		order.verify(exhibitionRepository).save(any(Exhibition.class));
		order.verify(exhibitionHistoryRepository).save(any());
		order.verify(eventPublisher).publishEvent(any(ExhibitionAdminUpdatedEvent.class));
	}
}
