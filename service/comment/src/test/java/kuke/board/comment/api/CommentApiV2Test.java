package kuke.board.comment.api;

import kuke.board.comment.service.response.CommentPageResponse;
import kuke.board.comment.service.response.CommentResponse;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

import java.util.List;

public class CommentApiV2Test {

    RestClient restClient = RestClient.create("http://localhost:9001");

    @Test
    void createTest() {
        CommentResponse response1 =  create(new CommentCreateRequestV2(1L, "my comment1", null, 1L));
        CommentResponse response2 =  create(new CommentCreateRequestV2(1L, "my comment2", response1.getPath(), 1L));
        CommentResponse response3 =  create(new CommentCreateRequestV2(1L, "my comment3", response2.getPath(), 1L));

        System.out.println("response1.getPath() = " + response1.getPath());
        System.out.println("response1.getCommentId() = " + response1.getCommentId());
        System.out.println("\tresponse2.getPath() = " + response2.getPath());
        System.out.println("\tresponse2.getCommentId() = " + response2.getCommentId());
        System.out.println("\t\tresponse3.getPath() = " + response3.getPath());
        System.out.println("\t\tresponse3.getCommentId() = " + response3.getCommentId());

//        response1.getPath() = 00000
//        response1.getCommentId() = 146521225906950144
//            response2.getPath() = 0000000000
//            response2.getCommentId() = 146521226297020416
//                response3.getPath() = 000000000000000
//                response3.getCommentId() = 146521226364129280
    }

    @Test
    void deleteTest() {
        restClient.delete()
                .uri("/v2/comments/{commentId}", 146521225906950144L)
                .retrieve();
    }

    @Test
    void readTest() {
        CommentResponse response =  restClient.get()
                .uri("/v2/comments/{commentId}", 146521225906950144L)
                .retrieve()
                .body(CommentResponse.class);
        System.out.println("response = " + response);
    }

    @Test
    void readAllTest() {
        CommentPageResponse response = restClient.get()
                .uri("/v2/comments?articleId=1&pageSize=10&page=50000")
                .retrieve()
                .body(CommentPageResponse.class);

        System.out.println("response.getCommentCount() = " + response.getCommentCount());
        for (CommentResponse comment: response.getComments()) {
            System.out.println("comment.getCommentId() = " + comment.getCommentId());
        }

        /**
         * comment.getCommentId() = 146523232506503173
         * comment.getCommentId() = 146523232544251909
         * comment.getCommentId() = 146523232544251917
         * comment.getCommentId() = 146523232544251926
         * comment.getCommentId() = 146523232548446212
         * comment.getCommentId() = 146523232548446216
         * comment.getCommentId() = 146523232548446221
         * comment.getCommentId() = 146523232548446224
         * comment.getCommentId() = 146523232548446230
         * comment.getCommentId() = 146523232548446237
         */
    }

    @Test
    void readAllInfiniteScrollTest() {
        List<CommentResponse> responses1 = restClient.get()
                .uri("/v2/comments/infinite-scroll?articleId=1&pageSize=5")
                .retrieve()
                .body(new ParameterizedTypeReference<List<CommentResponse>>() {
                });

        System.out.println("first page");
        for (CommentResponse comment: responses1) {
            System.out.println("comment.getCommentId() = " + comment.getCommentId());
        }

        String lastPath = responses1.getLast().getPath();

        List<CommentResponse> responses2 = restClient.get()
                .uri("/v2/comments/infinite-scroll?articleId=1&pageSize=5&lastPath=%s".formatted(lastPath))
                .retrieve()
                .body(new ParameterizedTypeReference<List<CommentResponse>>() {
                });

        System.out.println("second page");
        for (CommentResponse comment: responses2) {
            System.out.println("comment.getCommentId() = " + comment.getCommentId());
        }

        /**
         * first page
         * comment.getCommentId() = 146523232506503173
         * comment.getCommentId() = 146523232544251909
         * comment.getCommentId() = 146523232544251917
         * comment.getCommentId() = 146523232544251926
         * comment.getCommentId() = 146523232548446212
         * second page
         * comment.getCommentId() = 146523232548446216
         * comment.getCommentId() = 146523232548446221
         * comment.getCommentId() = 146523232548446224
         * comment.getCommentId() = 146523232548446230
         * comment.getCommentId() = 146523232548446237
         */
    }

    CommentResponse create(CommentCreateRequestV2 request) {
        return restClient.post()
                .uri("/v2/comments")
                .body(request)
                .retrieve()
                .body(CommentResponse.class);
    }

    @Test
    void countTest() {
        CommentResponse response = create(new CommentCreateRequestV2(2L, "my comment1", null, 1L));

        Long count1 = restClient.get()
                .uri("/v2/comments/articles/{articleId}/count", 2L)
                .retrieve()
                .body(Long.class);
        System.out.println("count1 = " + count1); // 1

        restClient.delete()
                .uri("/v2/comments/{commentId}", response.getCommentId())
                .retrieve();

        Long count2 = restClient.get()
                .uri("/v2/comments/articles/{articleId}/count", 2L)
                .retrieve()
                .body(Long.class);
        System.out.println("count2 = " + count2); // 0
    }

    @Getter
    @AllArgsConstructor
    public static class CommentCreateRequestV2 {

        private Long articleId;
        private String content;
        private String parentPath;
        private Long writerId;
    }
}
