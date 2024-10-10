package enterprise.test.infra.redis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewCacheRedis implements RedisService<ReviewCache, Long> {

    private final RedisTemplate<String, Object> redisTemplate;
    private static final String PREFIX = "post:";

    /**
     * 식별자를 받아서 키를 생성한 뒤 `Redis`에 저장합니다.
     * 이미 저장된 요소가 있다면 값을 증가시킵니다.
     * @param id 상품의 식별자
     * @param value 한 건의 리뷰와 평점
     */
    @Override
    public void setValue(Long id, ReviewCache value) {
        String key = generateKey(id);

        if (Boolean.FALSE.equals(redisTemplate.hasKey(key))) {
            Map<String, Object> map = new HashMap<>();
            map.put("count", String.valueOf(value.count()));
            map.put("sum", String.valueOf(value.sum()));

            redisTemplate.opsForHash().putAll(key, map);
        } else {
            incrementCount(key);
            incrementSum(key, value.sum());
        }
    }

    /**
     * 캐시에 누적된 리뷰 수와 평점의 총합을 반환한다
     * @param id 상품의 식별자
     * @return 리뷰의 총 누적 횟수와 평점의 총합
     */
    @Override
    public ReviewCache getValue(Long id) {
        String key = generateKey(id);
        Map<Object, Object> map = redisTemplate.opsForHash().entries(key);

        if (map.isEmpty()) {
            return null;
        }

        int count = Integer.parseInt((String) map.get("count"));
        int sum = Integer.parseInt((String) map.get("sum"));
        log.info("count: {}, sum: {}", count, sum);
        return new ReviewCache(count, sum);
    }

    /**
     * 저장된 모든 식별자를 반환
     * @return 저장된 모든 식별자 하나도 없다면 빈 `Set` 반환
     */
    @Override
    public Set<Long> keys() {
        Set<String> keys = redisTemplate.keys(PREFIX + "*");
        log.info("keys: {}", keys);

        if (keys == null || keys.isEmpty()) {
            return Collections.emptySet();
        }
        return keys.stream()
                .map(key -> key.replace(PREFIX, "")) // 'post:' 제거
                .map(Long::parseLong)                // 숫자 부분만 Long으로 변환
                .collect(Collectors.toSet());
    }

    @Override
    public ReviewCache getAndRemoveValue(Long key) {
        // 트랜잭션 내에서 실행할 로직을 SessionCallback으로 작성
        ReviewCache cache = getValue(key);
        return redisTemplate.execute(new SessionCallback<ReviewCache>() {
            @Override
            @SuppressWarnings("unchecked")
            public ReviewCache execute(RedisOperations operations) throws DataAccessException {

                try {
                    operations.multi(); // 트랜잭션 시작
                    deleteValue(key); // 값을 삭제하는 명령을 트랜잭션에 추가
                    operations.exec(); // 트랜잭션 종료

                } catch (Exception e) {
                    e.printStackTrace();
                    setValue(key, cache); // 트랜잭션 실패 시 원래 값을 복구
                    operations.discard();
                }
                return cache;
            }
        });
    }

    @Override
    public void deleteValue(Long id) {
        String key = generateKey(id);
        redisTemplate.delete(key);
    }

    private void incrementCount(String key) {
        redisTemplate.opsForHash().increment(key, "count", 1);
    }

    private void incrementSum(String key, int sum) {
        redisTemplate.opsForHash().increment(key, "sum", sum);
    }

    private String generateKey(Long id) {
        return PREFIX + id;
    }
}
