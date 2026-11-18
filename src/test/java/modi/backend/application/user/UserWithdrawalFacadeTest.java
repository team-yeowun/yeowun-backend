package modi.backend.application.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import modi.backend.domain.auth.RefreshTokenStore;
import modi.backend.domain.notification.Notification;
import modi.backend.domain.record.Record;
import modi.backend.domain.remind.Remind;
import modi.backend.domain.user.SocialAccount;
import modi.backend.domain.user.User;
import modi.backend.domain.user.UserRepository;
import modi.backend.domain.bookmark.ExhibitionBookmarkRepository;
import modi.backend.domain.exhibition.catalog.ExhibitionRepository;
import modi.backend.infra.notification.NotificationJpaRepository;
import modi.backend.infra.record.RecordJpaRepository;
import modi.backend.infra.remind.RemindJpaRepository;
import modi.backend.infra.user.SocialAccountJpaRepository;
import modi.backend.support.error.CoreException;

/**
 * 회원 탈퇴 cascade 검증.
 *
 * <p>탈퇴하면 사용자가 만든 것도 함께 사라져야 하고(soft-delete), 소셜 연결은 실제로 지워져야 한다
 * — 남겨 두면 (provider, providerUserId) 유니크 제약 때문에 같은 소셜로 재가입할 수 없다(2026-07-19 운영 리포트).
 */
class UserWithdrawalFacadeTest {

	private UserRepository userRepository;
	private SocialAccountJpaRepository socialAccountJpaRepository;
	private RecordJpaRepository recordJpaRepository;
	private ExhibitionBookmarkRepository bookmarkRepository;
	private ExhibitionRepository exhibitionRepository;
	private RemindJpaRepository remindJpaRepository;
	private NotificationJpaRepository notificationJpaRepository;
	private RefreshTokenStore refreshTokenStore;
	private UserWithdrawalFacade facade;

	@BeforeEach
	void setUp() {
		userRepository = mock(UserRepository.class);
		socialAccountJpaRepository = mock(SocialAccountJpaRepository.class);
		recordJpaRepository = mock(RecordJpaRepository.class);
		bookmarkRepository = mock(ExhibitionBookmarkRepository.class);
		exhibitionRepository = mock(ExhibitionRepository.class);
		remindJpaRepository = mock(RemindJpaRepository.class);
		notificationJpaRepository = mock(NotificationJpaRepository.class);
		refreshTokenStore = mock(RefreshTokenStore.class);

		given(recordJpaRepository.findByUserIdAndDeletedAtIsNull(anyLong())).willReturn(List.of());
		given(bookmarkRepository.removeAllOf(anyLong())).willReturn(List.of());
		given(remindJpaRepository.findByUserIdAndDeletedAtIsNull(anyLong())).willReturn(List.of());
		given(notificationJpaRepository.findByUserIdAndDeletedAtIsNull(anyLong())).willReturn(List.of());
		given(socialAccountJpaRepository.findByUserIdAndDeletedAtIsNull(anyLong())).willReturn(List.of());

		facade = new UserWithdrawalFacade(userRepository, socialAccountJpaRepository, recordJpaRepository,
				bookmarkRepository, exhibitionRepository, remindJpaRepository, notificationJpaRepository,
				refreshTokenStore);
	}

	@Test
	@DisplayName("탈퇴: 사용자 본인 soft-delete + refresh 토큰 무효화")
	void 탈퇴_본인_삭제() {
		User user = User.createGuest();
		given(userRepository.findById(1L)).willReturn(Optional.of(user));

		facade.withdraw(1L);

		assertThat(user.getDeletedAt()).isNotNull();
		verify(userRepository).save(user);
		verify(refreshTokenStore).remove(1L);
	}

	@Test
	@DisplayName("탈퇴: 사용자가 만든 기록·리마인드·알림도 함께 soft-delete")
	void 탈퇴_콘텐츠_cascade() {
		given(userRepository.findById(1L)).willReturn(Optional.of(User.createGuest()));
		Record record = mock(Record.class);
		Remind remind = mock(Remind.class);
		Notification notification = mock(Notification.class);
		given(recordJpaRepository.findByUserIdAndDeletedAtIsNull(1L)).willReturn(List.of(record));
		given(remindJpaRepository.findByUserIdAndDeletedAtIsNull(1L)).willReturn(List.of(remind));
		given(notificationJpaRepository.findByUserIdAndDeletedAtIsNull(1L)).willReturn(List.of(notification));

		facade.withdraw(1L);

		verify(record).delete();
		verify(remind).delete();
		verify(notification).delete();
		// 저장 지점을 코드에 남긴다(dirty checking 의존 금지 — 컨벤션)
		verify(recordJpaRepository).saveAll(List.of(record));
		verify(remindJpaRepository).saveAll(List.of(remind));
		verify(notificationJpaRepository).saveAll(List.of(notification));
	}

	@Test
	@DisplayName("탈퇴: 북마크를 모두 해제하고, 해제한 전시마다 북마크 수(인기 점수)를 1씩 뺀다")
	void 탈퇴_북마크_해제와_수_감소() {
		given(userRepository.findById(1L)).willReturn(Optional.of(User.createGuest()));
		given(bookmarkRepository.removeAllOf(1L)).willReturn(List.of(10L, 11L));

		facade.withdraw(1L);

		verify(exhibitionRepository).changeBookmarkCount(10L, -1);
		verify(exhibitionRepository).changeBookmarkCount(11L, -1);
	}

	@Test
	@DisplayName("탈퇴: 소셜 연결은 soft-delete가 아니라 실제로 삭제한다(같은 소셜로 재가입 가능해야 함)")
	void 탈퇴_소셜연결_하드삭제() {
		given(userRepository.findById(1L)).willReturn(Optional.of(User.createGuest()));
		SocialAccount social = SocialAccount.create(1L, "naver", "sub-1", "a@b.com");
		given(socialAccountJpaRepository.findByUserIdAndDeletedAtIsNull(1L)).willReturn(List.of(social));

		facade.withdraw(1L);

		// soft-delete로 남기면 (provider, providerUserId) 유니크 제약에 걸려 재가입이 중복키로 깨진다
		verify(socialAccountJpaRepository).deleteAll(List.of(social));
		assertThat(social.getDeletedAt()).isNull();
	}

	@Test
	@DisplayName("탈퇴: 이미 탈퇴한 사용자는 USER_NOT_FOUND (콘텐츠도 건드리지 않음)")
	void 탈퇴_이미탈퇴_404() {
		given(userRepository.findById(1L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> facade.withdraw(1L)).isInstanceOf(CoreException.class);

		verify(userRepository, never()).save(any(User.class));
		verify(socialAccountJpaRepository, never()).deleteAll(any());
	}
}
