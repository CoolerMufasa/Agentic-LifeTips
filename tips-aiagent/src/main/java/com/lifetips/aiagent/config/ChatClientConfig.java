package com.lifetips.aiagent.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * DeepSeek ChatClient 配置。
 *
 * @author PCRao
 */
@Configuration
public class ChatClientConfig {

    @Value("${spring.ai.openai.api-key}")
    private String apiKey;

    @Value("${spring.ai.openai.base-url}")
    private String baseUrl;

    @Value("${spring.ai.openai.chat.options.model}")
    private String model;

    @Bean("deepseekChatClient")
    public ChatClient deepseekChatClient() {
        // 设置超时
        var requestFactory = new JdkClientHttpRequestFactory();
        requestFactory.setReadTimeout(Duration.ofSeconds(120));

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(this.baseUrl)
                .apiKey(this.apiKey)
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .build();

        // 控制temperature
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(this.model)
                        .temperature(0.0)
                        .build())
                .build();

        return ChatClient.builder(chatModel).build();
    }
}
