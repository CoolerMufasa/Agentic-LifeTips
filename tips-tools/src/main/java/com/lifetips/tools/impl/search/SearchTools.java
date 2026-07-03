package com.lifetips.tools.impl.search;

import com.lifetips.tools.IAgentTool;
import com.lifetips.tools.client.TavilyApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 搜索工具集。
 *
 * @author PCRao
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchTools implements IAgentTool {

    private final TavilyApiClient tavilyApiClient;

    /**
     * 网络搜索工具。
     */
    @Tool(
        name = "tavilySearch",
        description = "在互联网上搜索生活技巧、家居清洁方法、衣物护理知识、"
                      + "厨房烹饪技巧等内容。当用户提出具体的生活问题（如'红酒渍怎么洗'、"
                      + "'冰箱异味怎么除'）时使用此工具。输入搜索关键词，返回相关网页的摘要和内容片段。"
    )
    public String tavilySearch(
        @ToolParam(description = "搜索关键词，用中文，尽量具体。例如'白衬衫 红酒渍 清洗方法'而非'怎么洗'")
        String query
    ) {
        log.info("[Tool] tavilySearch 被调用, query={}", query);
        return tavilyApiClient.searchAsText(query, 5);
    }
}
