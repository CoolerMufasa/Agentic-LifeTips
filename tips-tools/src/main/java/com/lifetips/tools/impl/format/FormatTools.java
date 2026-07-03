package com.lifetips.tools.impl.format;


import com.lifetips.tools.IAgentTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 格式化工具类
 *
 * @author PCRao
 */
@Component
@Slf4j
public class FormatTools implements IAgentTool {

    @Tool(
            name = "formatLifeTip",
            description = "将搜索到的零散信息整理成结构化的生活小技巧，包含：所需材料、操作步骤、注意事项三个部分"
    )
    public String formatLifeTip(
            @ToolParam(description = "从搜索结果中提取的原始信息文本")
            String rawInfo
    ) {
        log.info("[TOOL] formatLifeTip，rawInfo={}", rawInfo);
        return """
                【生活小技巧】
                %s
                
                【所需材料】
                根据返回值整理
                
                【操作步骤】
                根据返回值整理
                
                【注意事项】
                根据返回值处理，可以适当强调
                """.formatted(rawInfo);
    }
}
