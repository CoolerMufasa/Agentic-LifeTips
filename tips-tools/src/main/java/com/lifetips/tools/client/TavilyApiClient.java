package com.lifetips.tools.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Tavily Search API 客户端。用 RestClient（同步）而非 WebClient（响应式），
 * 避免在 WebFlux 线程中 block() 导致的阻塞异常。
 *
 * @author PCRao
 */
@Slf4j
@Service
public class TavilyApiClient {

    private static final String API_URL = "https://api.tavily.com/search";
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;

    public TavilyApiClient(
            @Value("${tavily.api-key:}") String apiKey,
            ObjectMapper objectMapper
    ) {
        this.apiKey = apiKey;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder()
                .baseUrl(API_URL)
                .build();

        if (apiKey.isBlank()) {
            log.warn("tavily.api-key 未配置，搜索功能将不可用");
        }
    }

    public String searchAsText(String query, int maxResults) {
        log.info("[Tavily] 搜索: query={}, maxResults={}", query, maxResults);

        try {
            String requestBody = objectMapper.writeValueAsString(Map.of(
                    "api_key", apiKey,
                    "query", query,
                    "max_results", Math.min(maxResults, 10),
                    "search_depth", "basic",
                    "include_answer", true
            ));

            String response = restClient.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            return formatResponse(response);
        } catch (Exception e) {
            log.error("[Tavily] 搜索异常: {}", e.getMessage(), e);
            return "【搜索异常】" + e.getMessage();
        }
    }

    private String formatResponse(String jsonResponse) {
        try {
            JsonNode root = objectMapper.readTree(jsonResponse);

            if (root.has("error")) {
                return "搜索未返回结果: " + root.get("error").asText();
            }

            StringBuilder sb = new StringBuilder();
            String answer = root.path("answer").asText("");
            if (!answer.isBlank()) {
                sb.append("【摘要】").append(answer).append("\n\n");
            }

            JsonNode results = root.path("results");
            if (results.isArray()) {
                sb.append("【搜索结果】\n");
                for (int i = 0; i < results.size(); i++) {
                    JsonNode item = results.get(i);
                    sb.append(String.format(
                            "%d. %s\n   URL: %s\n   %s\n\n",
                            i + 1,
                            item.path("title").asText("无标题"),
                            item.path("url").asText(""),
                            item.path("content").asText("无内容")
                    ));
                }
            }

            String formatted = sb.toString();
            return formatted.isBlank() ? "未找到相关信息，建议更换搜索关键词" : formatted;
        } catch (Exception e) {
            log.warn("[Tavily] 格式化响应失败: {}", e.getMessage());
            return jsonResponse;
        }
    }
}
