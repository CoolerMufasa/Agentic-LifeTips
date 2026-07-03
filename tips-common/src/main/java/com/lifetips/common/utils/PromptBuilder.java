package com.lifetips.common.utils;

import com.lifetips.common.annotation.PromptField;

import java.lang.reflect.Field;

/**
 * prompt组装方法
 *
 * @author PCRao
 */
public class PromptBuilder {

    private PromptBuilder() {
    }

    public static String buildJsonExample(Class<?> clazz) {
        Field[] fields = clazz.getDeclaredFields();
        StringBuilder promptString = new StringBuilder("{\n");

        for (int i = 0; i < fields.length; i++) {
            Field field = fields[i];
            PromptField promptField = field.getAnnotation(PromptField.class);
            if (promptField == null) {
                continue;
            }

            promptString.append(String.format("\"%s\": \"%s\"", field.getName(), promptField.value()));
            // 如果没有写example的话就不去拼接
            if (!promptField.example().isBlank()) {
                promptString.append(String.format("（例如：\"%s\"）", promptField.example()));
            }

            if (i < fields.length - 1) {
                boolean hasNextAnnotated = false;
                for (int j = i + 1; j < fields.length; j++) {
                    if (fields[j].getAnnotation(PromptField.class) != null) {
                        hasNextAnnotated = true;
                        break;
                    }
                }
                if (hasNextAnnotated) promptString.append(",");
            }
            promptString.append("\n");
        }
        promptString.append("}");
        return promptString.toString();
    }
}
