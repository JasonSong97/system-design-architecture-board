package kuke.board.comment.data;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import kuke.board.comment.entity.Comment;
import kuke.board.common.snowflake.Snowflake;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SpringBootTest
public class DataInitializer {
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
            executorService.submit(() -> { // 멀티스레드에서 삽입
                insert();
                latch.countDown();
                System.out.println("latch,getCount() = " + latch.getCount());
            });
        }

        latch.await(); // 0이 될 때까지 기다림
        executorService.shutdown();
    }

    void insert() {
        transactionTemplate.executeWithoutResult(status -> {
            Comment prev = null; // 이전 댓글
            for (int i = 0; i < BULK_INSERT_SIZE; i++) { // 2000개 수행
                Comment comment = Comment.create(
                        snowflake.nextId(),
                        "content",
                        i % 2 == 0 ? null : prev.getCommentId(),
                        1L,
                        1L
                );
                prev = comment;
                entityManager.persist(comment); // 데이터 영구 저장
            }
        });
    }
}
