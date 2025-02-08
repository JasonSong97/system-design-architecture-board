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

    CommentResponse create(CommentCreateRequestV2 request) {
        return restClient.post()
                .uri("/v2/comments")
                .body(request)
                .retrieve()
                .body(CommentResponse.class);
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
