package com.lifetips.tools.impl.knowledge;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.rag.DashScopeDocumentRetriever;
import com.alibaba.cloud.ai.dashscope.rag.DashScopeDocumentRetrieverOptions;
import com.lifetips.tools.IAgentTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 知识检索工具集，提供百炼云 DashScope 知识库检索能力。
 *
 * @author PCRao
 */
@Slf4j
@Component
public class KnowledgeTools implements IAgentTool {

    // 百炼云文档检索器
    private final DashScopeDocumentRetriever retriever;

    public KnowledgeTools(
            DashScopeApi dashScopeApi,
            @Value("${dashscope.knowledge-base.index-name:life-tips-knowledge}") String indexName) {
        this.retriever = new DashScopeDocumentRetriever(dashScopeApi,
                DashScopeDocumentRetrieverOptions.builder()
                        .withIndexName(indexName)
                        .denseSimilarityTopK(5)
                        .build());
        log.info("[KnowledgeTools] 已初始化, indexName={}", indexName);
    }

    /**
     * 百炼云知识库检索。
     */
    @Tool(
        name = "dashScopeRetrieve",
        description = "查询食材保鲜知识库。覆盖食品安全标准、保质期、变质判断标准、"
                    + "保存方法等确定性知识。适用于需要准确、权威信息的场景。"
                    + "输入：查询关键词。输出：匹配的知识库文档内容。"
    )
    public String dashScopeRetrieve(
        @ToolParam(description = "查询关键词，如'豆腐 变质判断标准'、'鸡蛋 保质期 冷藏'")
        String query
    ) {
        log.info("[Tool] dashScopeRetrieve 被调用, query={}", query);

        try {
            List<Document> docs = retriever.retrieve(
                    Query.builder().text(query).build());

            if (docs == null || docs.isEmpty()) {
                return "知识库中未找到与「" + query + "」相关的内容";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("【知识库检索结果】\n");
            for (int i = 0; i < docs.size(); i++) {
                Document doc = docs.get(i);
                String text = doc.getText() != null ? doc.getText()
                        : (doc.getId() != null ? doc.getId() : "无内容");
                sb.append(String.format("%d. %s\n\n", i + 1, text));
            }
            return sb.toString();

        } catch (Exception e) {
            log.error("[Tool] dashScopeRetrieve 异常: {}", e.getMessage(), e);
            return "知识库检索失败: " + e.getMessage();
        }
    }
}
