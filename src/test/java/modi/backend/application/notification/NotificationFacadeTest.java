package modi.backend.application.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import modi.backend.application.remind.RemindFacade;
import modi.backend.application.remind.RemindResult;
import modi.backend.domain.bookmark.ExhibitionBookmarkRepository;
import modi.backend.domain.exhibition.catalog.Exhibition;
import modi.backend.domain.exhibition.catalog.ExhibitionRepository;
import modi.backend.domain.notification.Notification;
import modi.backend.domain.notification.NotificationRepository;
import modi.backend.domain.notification.NotificationType;
import modi.backend.domain.user.User;
import modi.backend.domain.user.UserRepository;
import modi.backend.support.time.AppTime;

/**
 * lazy 알림 생성(refresh) 유스케이스 단위 검증 — 저장소·리마인드 판정을 모킹해
 * "만들어야 할 때만, 한 번만" 만드는지와 문구 규칙을 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class NotificationFacadeTest {

	@Mock
	NotificationRepository notificationRepository;

	@Mock
	RemindFacade remindFacade;

	@Mock
	UserRepository userRepository;

	@Mock
	ExhibitionBookmarkRepository exhibitionBookmarkRepository;

	@Mock
	ExhibitionRepository exhibitionRepository;

	@InjectMocks
	NotificationFacade facade;

	@Test
	@DisplayName("refresh — 소환 후보가 있고 알림이 없으면 REMIND 생성(닉네임 포함 문구, targetId=recordId)")
	void refresh_remind_생성() {
		given(remindFacade.candidate(1L)).willReturn(candidate(10L, "1주일 전"));
		given(notificationRepository.existsByUserIdAndTypeAndTargetId(1L, NotificationType.REMIND, 10L))
				.willReturn(false);
		given(userRepository.findById(1L)).willReturn(Optional.of(User.createFromSocial("시현")));
		given(exhibitionBookmarkRepository.findActiveExhibitionIdsByUserIdOrderByRegisteredDesc(1L))
				.willReturn(List.of());

		facade.refresh(1L);

		Notification saved = capturedNotification();
		assertThat(saved.getType()).isEqualTo(NotificationType.REMIND);
		assertThat(saved.getTitle()).isEqualTo("오늘의 여운");
		assertThat(saved.getBody()).isEqualTo("시현님, 1주일 전 기록한 전시가 있어요!");
		assertThat(saved.getTargetId()).isEqualTo(10L);
		assertThat(saved.getImageUrl()).isEqualTo("https://img/monet-poster.jpg");
		assertThat(saved.isRead()).isFalse();
	}

	@Test
	@DisplayName("refresh — 같은 기록의 REMIND 알림이 이미 있으면 다시 만들지 않는다(멱등)")
	void refresh_remind_멱등() {
		given(remindFacade.candidate(1L)).willReturn(candidate(10L, "1주일 전"));
		given(notificationRepository.existsByUserIdAndTypeAndTargetId(1L, NotificationType.REMIND, 10L))
				.willReturn(true);
		given(exhibitionBookmarkRepository.findActiveExhibitionIdsByUserIdOrderByRegisteredDesc(1L))
				.willReturn(List.of());

		facade.refresh(1L);

		verify(notificationRepository, never()).save(any());
	}

	@Test
	@DisplayName("refresh — 닉네임을 모르면 호칭 없이 문구를 만든다")
	void refresh_remind_닉네임없음() {
		given(remindFacade.candidate(1L)).willReturn(candidate(10L, "오늘"));
		given(notificationRepository.existsByUserIdAndTypeAndTargetId(1L, NotificationType.REMIND, 10L))
				.willReturn(false);
		given(userRepository.findById(1L)).willReturn(Optional.empty());
		given(exhibitionBookmarkRepository.findActiveExhibitionIdsByUserIdOrderByRegisteredDesc(1L))
				.willReturn(List.of());

		facade.refresh(1L);

		assertThat(capturedNotification().getBody()).isEqualTo("오늘 기록한 전시가 있어요!");
	}

	@Test
	@DisplayName("refresh — 소환 후보도 북마크도 없으면 아무것도 만들지 않는다")
	void refresh_대상없음() {
		given(remindFacade.candidate(1L)).willReturn(null);
		given(exhibitionBookmarkRepository.findActiveExhibitionIdsByUserIdOrderByRegisteredDesc(1L))
				.willReturn(List.of());

		facade.refresh(1L);

		verify(notificationRepository, never()).save(any());
	}

	@Test
	@DisplayName("refresh — 북마크 전시가 3일 뒤 종료면 EXHIBITION 생성(문구·targetId=exhibitionId)")
	void refresh_exhibition_생성() {
		LocalDate today = LocalDate.now(AppTime.KST);
		given(remindFacade.candidate(1L)).willReturn(null);
		given(exhibitionBookmarkRepository.findActiveExhibitionIdsByUserIdOrderByRegisteredDesc(1L))
				.willReturn(List.of(100L));
		given(exhibitionRepository.findActiveByIdsAndEndDateBetween(List.of(100L), today, today.plusDays(3)))
				.willReturn(List.of(exhibition(100L, "행복한 인생", today.plusDays(3))));
		given(notificationRepository.existsByUserIdAndTypeAndTargetId(1L, NotificationType.EXHIBITION, 100L))
				.willReturn(false);

		facade.refresh(1L);

		Notification saved = capturedNotification();
		assertThat(saved.getType()).isEqualTo(NotificationType.EXHIBITION);
		assertThat(saved.getTitle()).isEqualTo("전시");
		assertThat(saved.getBody()).isEqualTo("'행복한 인생' 전시가 3일 뒤 종료 돼요");
		assertThat(saved.getTargetId()).isEqualTo(100L);
		assertThat(saved.getImageUrl()).isEqualTo("https://img/100-poster.jpg");
	}

	@Test
	@DisplayName("refresh — 오늘 종료하는 전시는 \"오늘 종료 돼요\" 문구로 생성")
	void refresh_exhibition_오늘종료() {
		LocalDate today = LocalDate.now(AppTime.KST);
		given(remindFacade.candidate(1L)).willReturn(null);
		given(exhibitionBookmarkRepository.findActiveExhibitionIdsByUserIdOrderByRegisteredDesc(1L))
				.willReturn(List.of(100L));
		given(exhibitionRepository.findActiveByIdsAndEndDateBetween(List.of(100L), today, today.plusDays(3)))
				.willReturn(List.of(exhibition(100L, "행복한 인생", today)));
		given(notificationRepository.existsByUserIdAndTypeAndTargetId(1L, NotificationType.EXHIBITION, 100L))
				.willReturn(false);

		facade.refresh(1L);

		assertThat(capturedNotification().getBody()).isEqualTo("'행복한 인생' 전시가 오늘 종료 돼요");
	}

	@Test
	@DisplayName("refresh — 종료임박 선별을 조회 조건(오늘~+3일)으로 넘긴다")
	void refresh_exhibition_창을_조회조건으로_넘긴다() {
		LocalDate today = LocalDate.now(AppTime.KST);
		given(remindFacade.candidate(1L)).willReturn(null);
		given(exhibitionBookmarkRepository.findActiveExhibitionIdsByUserIdOrderByRegisteredDesc(1L))
				.willReturn(List.of(100L, 101L, 102L));

		facade.refresh(1L);

		// 4일 남음·이미 종료·종료일 미상을 앱에서 거르지 않는다 — 창을 WHERE로 넘겨 대상만 읽는다.
		verify(exhibitionRepository).findActiveByIdsAndEndDateBetween(
				List.of(100L, 101L, 102L), today, today.plusDays(3));
		verify(notificationRepository, never()).save(any());
	}

	@Test
	@DisplayName("refresh — 같은 전시의 EXHIBITION 알림이 이미 있으면 다시 만들지 않는다(멱등)")
	void refresh_exhibition_멱등() {
		LocalDate today = LocalDate.now(AppTime.KST);
		given(remindFacade.candidate(1L)).willReturn(null);
		given(exhibitionBookmarkRepository.findActiveExhibitionIdsByUserIdOrderByRegisteredDesc(1L))
				.willReturn(List.of(100L));
		given(exhibitionRepository.findActiveByIdsAndEndDateBetween(List.of(100L), today, today.plusDays(3)))
				.willReturn(List.of(exhibition(100L, "행복한 인생", today.plusDays(1))));
		given(notificationRepository.existsByUserIdAndTypeAndTargetId(1L, NotificationType.EXHIBITION, 100L))
				.willReturn(true);

		facade.refresh(1L);

		verify(notificationRepository, never()).save(any());
	}

	@Test
	@DisplayName("getNotifications — type 필터를 목록·건수 조회에 그대로 전달한다")
	void getNotifications_type필터_전달() {
		given(notificationRepository.findPage(1L, NotificationType.NOTICE, null, null, 21))
				.willReturn(List.of());
		given(notificationRepository.countByUserId(1L, NotificationType.NOTICE)).willReturn(0L);

		NotificationResult.List result = facade.getNotifications(
				new NotificationCriteria.List(1L, NotificationType.NOTICE, null, null));

		assertThat(result.content()).isEmpty();
		assertThat(result.totalCount()).isZero();
		verify(notificationRepository).findPage(1L, NotificationType.NOTICE, null, null, 21);
		verify(notificationRepository).countByUserId(1L, NotificationType.NOTICE);
	}

	private Notification capturedNotification() {
		ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
		verify(notificationRepository).save(captor.capture());
		return captor.getValue();
	}

	private RemindResult.Candidate candidate(Long recordId, String elapsedLabel) {
		return new RemindResult.Candidate(recordId, 7, elapsedLabel, 20L, "모네전", null,
				"https://img/monet-poster.jpg", null,
				"예술의전당", "SEOUL", LocalDate.of(2026, 6, 20), "원본 감상", List.of("평화로운"));
	}

	/** 테스트용 전시 — id는 영속 전 엔티티라 리플렉션으로 부여(엔티티에 setter를 만들지 않기 위함). */
	private Exhibition exhibition(Long id, String title, LocalDate endDate) {
		LocalDate startDate = LocalDate.now(AppTime.KST).minusDays(30);
		Exhibition exhibition = Exhibition.createCustom(99L, title, 1L, startDate, endDate,
				null, null, null, "https://img/" + id + "-poster.jpg");
		ReflectionTestUtils.setField(exhibition, "id", id);
		return exhibition;
	}
}
