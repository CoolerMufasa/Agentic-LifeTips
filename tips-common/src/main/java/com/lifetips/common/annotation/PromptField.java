package com.lifetips.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记 VO 字段在 SystemPrompt 中的描述和示例值。
 *
 * @author PCRao
 */
@Target(ElementType.FIELD)      // 只允许用在字段上
@Retention(RetentionPolicy.RUNTIME)  // 保留到运行时，反射可读取
public @interface PromptField {

    // 字段含义描述，会出现在 SystemPrompt 的 JSON 格式说明中
    String value();

    // 示例值（可选），帮助 LLM 理解字段应填什么格式
    String example() default "";
}
