package com.lifetips.aiagent.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * DeepSeek 双模型 ChatClient 配置。planner 连 v4-pro（深度推理），worker 连 v4-flash（快速执行）。
 *
 * @author PCRao
 */
@Configuration
public class ChatClientConfig {

    @Value("${spring.ai.openai.api-key}")
    private String apiKey;

    @Value("${spring.ai.openai.base-url}")
    private String baseUrl;

    @Value("${spring.ai.openai.planner.model}")
    private String plannerModel;

    @Value("${spring.ai.openai.worker.model}")
    private String workerModel;

    /**
     * Planner ChatClient，连 v4-pro，负责深度推理（生成假设、更新状态）。
     */
    @Primary
    @Bean("plannerChatClient")
    public ChatClient plannerChatClient() {
        return buildChatClient(plannerModel);
    }

    /**
     * Worker ChatClient，连 v4-flash，负责快速执行（工具调用、首轮评估）。
     */
    @Bean("workerChatClient")
    public ChatClient workerChatClient() {
        return buildChatClient(workerModel);
    }

    private ChatClient buildChatClient(String model) {
        var requestFactory = new JdkClientHttpRequestFactory();
        requestFactory.setReadTimeout(Duration.ofSeconds(120));

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(this.baseUrl)
                .apiKey(this.apiKey)
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .build();

        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(model)
                        .temperature(0.0)
                        .build())
                .build();

        return ChatClient.builder(chatModel).build();
    }
}
