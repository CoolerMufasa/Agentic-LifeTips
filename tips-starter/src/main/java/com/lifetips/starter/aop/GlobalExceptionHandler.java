package com.lifetips.starter.aop;


import com.lifetips.common.exception.BizException;
import com.lifetips.starter.vo.ServiceResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理类
 *
 * @author PCRao
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * 自定义业务异常拦截
     */
    @ExceptionHandler(BizException.class)
    public ServiceResponse handleBizException(BizException e) {
        log.warn("业务拦截 - 错误码: {}, 错误信息: {}", e.getCode(), e.getMessage());
        ServiceResponse bizExceptionResponse = new ServiceResponse();
        bizExceptionResponse.setCode(e.getCode());
        bizExceptionResponse.setMessage(e.getMessage());
        return bizExceptionResponse;
    }

    /**
     * 捕获非业务异常
     */
    @ExceptionHandler(Exception.class)
    public ServiceResponse handleOtherBizException(Exception e){
        log.error("其他错误拦截", e);
        ServiceResponse bizExceptionResponse = new ServiceResponse();
        bizExceptionResponse.setCode("SYSTEM_ERROR");
        bizExceptionResponse.setMessage("系统内部错误");
        return bizExceptionResponse;
    }
}
