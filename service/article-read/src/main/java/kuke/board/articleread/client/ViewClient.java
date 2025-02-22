package kuke.board.articleread.client;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Slf4j
@Component
@RequiredArgsConstructor
public class ViewClient {

    private RestClient restClient;
    @Value("${endpoints.kuke-board-view-service.url}")
    private String viewServiceUrl;

    @PostConstruct
    public void initRestClient() {
        restClient = RestClient.create(viewServiceUrl);
    }

    // redis에서 데이터를 조회해본다.
    // 레디스에 데이터가 없었다면, count 메소드 내부로직이 호출되면서 viewService로 원본데이터롤 요청한다. 그리고 redis에 데이터를 넣고 응답힌다.
    // 레디스에 데이터가 있다면, 그 데이터를 그대로 반환한다.
    @Cacheable(key = "#articleId", value = "articleViewCount")
    public long count(Long articleId) {
        log.info("[ViewClient.count] articleId={}", articleId);
        try {
            return restClient.get()
                    .uri("/v1/article-views/articles/{articleId}/count", articleId)
                    .retrieve()
                    .body(Long.class);
        } catch (Exception e) {
            log.error("[ViewClient.count] articleId={}", articleId, e);
            return 0;
        }
    }
}
