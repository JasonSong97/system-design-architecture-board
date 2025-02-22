package kuke.board.articleread.api;

import kuke.board.articleread.service.response.ArticleReadResponse;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

public class ArticleReadApiTest {

    RestClient restClient = RestClient.create("http://localhost:9005");

    @Test
    void readTest() {
        ArticleReadResponse response = restClient.get()
                .uri("/v1/articles/{articleId}", 145539405443198976L)
                .retrieve()
                .body(ArticleReadResponse.class);

        System.out.println("response = " + response);

    }
}
