package modi.backend.application.user;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import modi.backend.domain.auth.RefreshTokenStore;
import modi.backend.domain.bookmark.ExhibitionBookmarkRepository;
import modi.backend.domain.exhibition.catalog.ExhibitionRepository;
import modi.backend.domain.user.SocialAccount;
import modi.backend.domain.user.User;
import modi.backend.domain.user.UserErrorCode;
import modi.backend.domain.user.UserRepository;
import modi.backend.infra.notification.NotificationJpaRepository;
import modi.backend.infra.record.RecordJpaRepository;
import modi.backend.infra.remind.RemindJpaRepository;
import modi.backend.infra.user.SocialAccountJpaRepository;
import modi.backend.support.entity.BaseEntity;
import modi.backend.support.error.CoreException;

/**
 * 회원 탈퇴 유스케이스. 사용자 본인 + 사용자가 만든 콘텐츠를 한 트랜잭션에서 정리한다.
 *
 * <p>탈퇴 cascade는 여러 도메인(record·bookmark·remind·notification)에 걸쳐 있어
 * {@link UserFacade}(프로필 조회/수정)와 분리했다 — 한 Facade에 리포지토리가 몰리는 것을 피한다.
 * 도메인 포트가 없는 record·remind는 이 코드베이스의 기존 방식대로 Spring Data 리포지토리를 직접 쓴다
 * (UserFacade의 통계 집계와 동일한 실용 선택). <b>상태 변경은 전부 {@code entity.delete()}</b>(Entity 메서드)로 한다.
 *
 * <p><b>콘텐츠는 soft-delete, 소셜 연결만 hard-delete</b>로 비대칭인 이유:
 * <ul>
 *   <li>콘텐츠(기록·북마크·리마인드·알림) — 이 코드베이스는 전 계층이 soft-delete 전제라 일관되게 맞춘다.
 *       조회는 모두 {@code deletedAtIsNull}로 걸러지므로 사용자 눈엔 즉시 사라진다.</li>
 *   <li>소셜 연결 — provider가 준 <b>개인식별자</b>라 탈퇴 시 실제로 지운다. 또한
 *       {@code (provider, providerUserId)} 유니크 제약이 {@code deleted_at}을 포함하지 않아,
 *       남겨 두면 같은 소셜로 재가입할 때 중복키로 깨진다.</li>
 * </ul>
 *
 * <p>⚠️ 업로드된 이미지 원본(R2)은 여기서 지우지 못한다 — media-worker에 삭제 엔드포인트가 없다
 * (현재 {@code POST /presign}만). 실제 파기는 삭제 API 추가 후 별도 purge 작업에서 처리한다.
 */
@Service
@RequiredArgsConstructor
public class UserWithdrawalFacade {

	private final UserRepository userRepository;
	private final SocialAccountJpaRepository socialAccountJpaRepository;
	private final RecordJpaRepository recordJpaRepository;
	private final ExhibitionBookmarkRepository exhibitionBookmarkRepository;
	private final ExhibitionRepository exhibitionRepository;
	private final RemindJpaRepository remindJpaRepository;
	private final NotificationJpaRepository notificationJpaRepository;
	private final RefreshTokenStore refreshTokenStore;

	/**
	 * 회원 탈퇴: 사용자가 만든 콘텐츠를 soft-delete하고, 소셜 연결을 지운 뒤 본인을 soft-delete한다.
	 * 마지막으로 refresh 토큰을 무효화한다. 이미 탈퇴한 사용자는 조회되지 않아 USER_NOT_FOUND.
	 */
	@Transactional
	public void withdraw(Long userId) {
		User user = userRepository.findById(userId)
				.orElseThrow(() -> new CoreException(UserErrorCode.USER_NOT_FOUND));

		softDeleteAll(recordJpaRepository, recordJpaRepository.findByUserIdAndDeletedAtIsNull(userId));
		// 북마크는 해제한 만큼 전시의 북마크 수(인기 점수, V62)에서도 뺀다 — 같은 트랜잭션이라 함께 커밋된다.
		exhibitionBookmarkRepository.removeAllOf(userId)
				.forEach(exhibitionId -> exhibitionRepository.changeBookmarkCount(exhibitionId, -1));
		softDeleteAll(remindJpaRepository, remindJpaRepository.findByUserIdAndDeletedAtIsNull(userId));
		softDeleteAll(notificationJpaRepository,
				notificationJpaRepository.findByUserIdAndDeletedAtIsNull(userId));

		// 소셜 연결은 실제로 제거(위 javadoc 참고 — 개인식별자 + 재가입 시 유니크 제약 충돌).
		List<SocialAccount> socials = socialAccountJpaRepository.findByUserIdAndDeletedAtIsNull(userId);
		socialAccountJpaRepository.deleteAll(socials);

		user.delete();
		userRepository.save(user);
		refreshTokenStore.remove(userId);
	}

	/** soft-delete는 Entity 메서드로 수행하고 저장을 명시 호출한다(dirty checking 의존 금지 — 컨벤션). */
	private <T extends BaseEntity> void softDeleteAll(JpaRepository<T, Long> repository, List<T> entities) {
		entities.forEach(BaseEntity::delete);
		repository.saveAll(entities);
	}
}
