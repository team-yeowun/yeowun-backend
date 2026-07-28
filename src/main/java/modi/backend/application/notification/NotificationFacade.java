package modi.backend.application.notification;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import modi.backend.application.remind.RemindFacade;
import modi.backend.application.remind.RemindResult;
import modi.backend.domain.bookmark.ExhibitionBookmarkRepository;
import modi.backend.domain.exhibition.catalog.Exhibition;
import modi.backend.domain.exhibition.catalog.ExhibitionRepository;
import modi.backend.domain.notification.Notification;
import modi.backend.domain.notification.NotificationErrorCode;
import modi.backend.domain.notification.NotificationRepository;
import modi.backend.domain.notification.NotificationType;
import modi.backend.domain.user.User;
import modi.backend.domain.user.UserRepository;
import modi.backend.support.error.CoreException;
import modi.backend.support.response.Cursor;
import modi.backend.support.time.AppTime;

/**
 * 알림 유스케이스 조율. 상태 변경·문구 규칙은 Entity 메서드, Facade는 load·조율·save만.
 * 목록은 (createdAt desc, id desc) 키셋 커서로 페이지네이션한다(정렬 판별자 "latest").
 * 알림 생성은 push가 아닌 lazy pull — 목록 조회 직전 {@link #refresh(Long)}가 조건을 판정해 채워 넣는다
 * (리마인드 소환 판정과 동일 철학). 크로스도메인 판정은 {@link RemindFacade}로의 단방향 위임(순환 없음).
 */
@Service
@RequiredArgsConstructor
public class NotificationFacade {

	/** 커서 정렬 판별자 — 최신순 단일 정렬. */
	private static final String SORT_LATEST = "latest";
	private static final int DEFAULT_SIZE = 20;
	private static final int MAX_SIZE = 50;
	/** 북마크 전시 종료 임박 판정 창 — KST 오늘 기준 남은 일수 0~3일이면 알린다. */
	private static final int EXHIBITION_ENDING_WINDOW_DAYS = 3;

	private final NotificationRepository notificationRepository;
	private final RemindFacade remindFacade;
	private final UserRepository userRepository;
	private final ExhibitionBookmarkRepository exhibitionBookmarkRepository;
	private final ExhibitionRepository exhibitionRepository;

	/**
	 * lazy 알림 생성(pull) — 목록 조회 전에 호출돼 조건을 만족한 알림을 만들어 둔다.
	 * 같은 (userId, type, targetId) 알림이 이미 있으면 만들지 않는다(멱등).
	 */
	@Transactional
	public void refresh(Long userId) {
		refreshRemind(userId);
		refreshExhibitionEnding(userId);
	}

	/** 내 알림 목록(최신순, 커서 페이지네이션). type=null이면 전체. 손상된 커서는 Cursor.decode가 INVALID_CURSOR로 처리한다. */
	@Transactional(readOnly = true)
	public NotificationResult.List getNotifications(NotificationCriteria.List criteria) {
		int size = clampSize(criteria.size());
		Optional<Cursor> cursor = Cursor.decode(criteria.cursor(), SORT_LATEST);
		ZonedDateTime cursorCreatedAt = cursor.map(c -> ZonedDateTime.parse(c.key())).orElse(null);
		Long cursorId = cursor.map(Cursor::lastId).orElse(null);

		List<Notification> rows = notificationRepository.findPage(criteria.userId(), criteria.type(),
				cursorCreatedAt, cursorId, size + 1);
		boolean hasNext = rows.size() > size;
		List<Notification> page = hasNext ? rows.subList(0, size) : rows;

		List<NotificationResult.Item> content = page.stream().map(NotificationResult.Item::from).toList();
		String nextCursor = hasNext ? nextCursorOf(page.get(page.size() - 1)) : null;
		long totalCount = notificationRepository.countByUserId(criteria.userId(), criteria.type());

		return new NotificationResult.List(content, nextCursor, hasNext, totalCount);
	}

	/** 알림 읽음 처리(멱등). 없거나 타인 알림이면 NOTIFICATION_NOT_FOUND(404). */
	@Transactional
	public NotificationResult.Read markRead(NotificationCriteria.Read criteria) {
		Notification notification = notificationRepository.findById(criteria.notificationId())
				.filter(n -> n.isOwnedBy(criteria.userId()))
				.orElseThrow(() -> new CoreException(NotificationErrorCode.NOTIFICATION_NOT_FOUND));
		notification.markRead();
		return NotificationResult.Read.from(notificationRepository.save(notification));
	}

	/** REMIND — 오늘의 소환 대상이 있고 아직 그 기록(recordId)의 알림이 없으면 생성. */
	private void refreshRemind(Long userId) {
		RemindResult.Candidate candidate = remindFacade.candidate(userId);
		if (candidate == null || notificationRepository.existsByUserIdAndTypeAndTargetId(
				userId, NotificationType.REMIND, candidate.recordId())) {
			return;
		}
		String nickname = userRepository.findById(userId).map(User::getNickname).orElse(null);
		notificationRepository.save(Notification.remind(userId, nickname, candidate.elapsedLabel(),
				candidate.recordId(), candidate.posterUrl()));
	}

	/** EXHIBITION — 북마크한 전시 중 KST 오늘 기준 0~3일 뒤 종료하는 전시마다(중복 제외) 생성. */
	private void refreshExhibitionEnding(Long userId) {
		List<Long> bookmarkedIds = exhibitionBookmarkRepository
				.findActiveExhibitionIdsByUserIdOrderByRegisteredDesc(userId);
		if (bookmarkedIds.isEmpty()) {
			return;
		}
		LocalDate today = LocalDate.now(AppTime.KST);
		// 종료임박(오늘~+N일) 선별을 WHERE로 내린다 — 예전엔 북마크 전량을 읽어 앱에서 D-day를 쟀다.
		// 종료일이 null인 전시는 BETWEEN에 걸리지 않아 자연히 빠진다(판정 불가 = 알림 없음, 기존 동작 동일).
		for (Exhibition exhibition : exhibitionRepository.findActiveByIdsAndEndDateBetween(
				bookmarkedIds, today, today.plusDays(EXHIBITION_ENDING_WINDOW_DAYS))) {
			long daysLeft = ChronoUnit.DAYS.between(today, exhibition.getEndDate());
			if (notificationRepository.existsByUserIdAndTypeAndTargetId(
					userId, NotificationType.EXHIBITION, exhibition.getId())) {
				continue;
			}
			notificationRepository.save(Notification.exhibitionEnding(
					userId, exhibition.getTitle(), (int) daysLeft, exhibition.getId(), exhibition.getPosterUrl()));
		}
	}

	private int clampSize(Integer size) {
		if (size == null || size < 1) {
			return DEFAULT_SIZE;
		}
		return Math.min(size, MAX_SIZE);
	}

	private String nextCursorOf(Notification last) {
		return Cursor.of(SORT_LATEST, last.getCreatedAt().toString(), last.getId()).encode();
	}
}
