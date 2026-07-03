package com.lifetips.common.exception;

import lombok.Getter;

/**
 * 业务异常。携带错误码，由 GlobalExceptionHandler 统一拦截后只对外暴露 code + message。
 *
 * @author PCRao
 */
@Getter
public class BizException extends RuntimeException {

    // 业务错误码，如 "TOOL_NOT_FOUND"、"PLANNER_TIMEOUT"
    private final String code;

    public BizException(String code, String message) {
        super(message);
        this.code = code;
    }

    public BizException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }
}
