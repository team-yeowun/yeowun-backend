package modi.backend.application.exhibition.view;

import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import modi.backend.domain.exhibition.catalog.ExhibitionRepository;

/**
 * 조회수 델타를 정본에 반영하는 트랜잭션 경계.
 *
 * <p>서비스 안의 {@code private} 메서드로 두지 않은 이유는 <b>자기 호출이면 프록시를 지나지 않아
 * {@code @Transactional}이 아무 일도 하지 않기 때문</b>이다. 벌크 갱신은 트랜잭션이 없으면
 * {@code TransactionRequiredException}으로 떨어지므로, 경계를 별도 빈으로 꺼내 확실히 프록시를 태운다.
 */
@Component
@RequiredArgsConstructor
public class ExhibitionViewCountApplier {

	private final ExhibitionRepository exhibitionRepository;

	/** 델타 전량을 한 트랜잭션으로 반영한다 — 일부만 반영되고 확정되는 상태를 만들지 않는다. */
	@Transactional
	public int apply(Map<Long, Long> deltas) {
		return exhibitionRepository.increaseViewCounts(deltas);
	}
}
