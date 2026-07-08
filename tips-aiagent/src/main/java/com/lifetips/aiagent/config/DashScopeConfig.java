package com.lifetips.aiagent.config;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 百炼云 DashScope API 配置，手动注册 DashScopeApi Bean。
 *
 * @author PCRao
 */
@Configuration
public class DashScopeConfig {

    // 百炼云 API Key
    @Value("${spring.ai.dashscope.api-key}")
    private String apiKey;

    // 业务空间 ID（可选）
    @Value("${spring.ai.dashscope.workspace-id:}")
    private String workspaceId;

    /**
     * 注册 DashScopeApi Bean，供 KnowledgeTools 使用。
     */
    @Bean
    public DashScopeApi dashScopeApi() {
        DashScopeApi.Builder builder = DashScopeApi.builder()
                .apiKey(apiKey);
        if (workspaceId != null && !workspaceId.isBlank()) {
            builder.workSpaceId(workspaceId);
        }
        return builder.build();
    }
}
