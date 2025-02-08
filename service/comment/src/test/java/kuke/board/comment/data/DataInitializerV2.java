package kuke.board.comment.data;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import kuke.board.comment.entity.Comment;
import kuke.board.comment.entity.CommentPath;
import kuke.board.comment.entity.CommentV2;
import kuke.board.common.snowflake.Snowflake;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SpringBootTest
public class DataInitializerV2 {
    // 1200만개의 데이터를 넣으려면 show-sql을 false로 잠시 바꾼다.

    @PersistenceContext
    EntityManager entityManager;
    @Autowired
    TransactionTemplate transactionTemplate;
    Snowflake snowflake = new Snowflake();
    CountDownLatch latch = new CountDownLatch(EXECUTE_COUNT);

    static final int BULK_INSERT_SIZE = 2000; // 1번에 2000개
    static final int EXECUTE_COUNT = 6000; // 총 6000번 x 2000개

    @Test
    void initialize() throws InterruptedException {
        ExecutorService executorService = Executors.newFixedThreadPool(10); // 스레드 풀 10개
        for (int i = 0; i < EXECUTE_COUNT; i++) { // 6000번 수행
            int start = i * BULK_INSERT_SIZE;
            int end = (i + 1) * BULK_INSERT_SIZE;
            executorService.submit(() -> { // 멀티스레드에서 삽입
                insert(start, end);
                latch.countDown();
                System.out.println("latch,getCount() = " + latch.getCount());
            });
        }

        latch.await(); // 0이 될 때까지 기다림
        executorService.shutdown();
    }

    void insert(int start, int end) {
        transactionTemplate.executeWithoutResult(status -> {
            for (int i = start; i < end; i++) { // 2000개 수행
                CommentV2 comment = CommentV2.create(
                        snowflake.nextId(),
                        "content",
                        1L,
                        1L,
                        toPath(i)
                );
                entityManager.persist(comment); // 데이터 영구 저장
            }
        });
    }

    private static final String CHARSET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int DEPTH_CHUNK_SIZE = 5;

    CommentPath toPath(int value) {
        String path = "";
        for (int i = 0; i < DEPTH_CHUNK_SIZE; i++) {
            path = CHARSET.charAt(value % CHARSET.length()) + path;
            value /= CHARSET.length();
        }
        return CommentPath.create(path);
    }
}
