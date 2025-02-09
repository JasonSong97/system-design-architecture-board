package kuke.board.view.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ArticleViewCountRepository {

    private final StringRedisTemplate redisTemplate; // redis 통신을 위한 주입

    // view::article::{article_id}::view_count <- key, value -> long 타입
    private static final String KEY_FORMAT = "view::article::%s::view_count";

    public Long read(Long articleId) {
        String result = redisTemplate.opsForValue().get(generateKey(articleId));
        return result == null ? 0L : Long.valueOf(result);
    }

    // 조회 수 증가 메소드
    public Long increase(Long articleId) {
        return redisTemplate.opsForValue().increment(generateKey(articleId));
    }

    private String generateKey(long articleId) {
        return KEY_FORMAT.formatted(articleId);
    }
}
