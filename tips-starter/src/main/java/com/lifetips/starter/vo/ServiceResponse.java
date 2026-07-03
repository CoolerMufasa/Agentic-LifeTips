package com.lifetips.starter.vo;


import lombok.Data;

import java.io.Serializable;

/**
 * 接口前端返回处理
 *
 * @author PCRao
 */
@Data
public class ServiceResponse implements Serializable {

    /**
     * 响应码
     */
    private String code;

    /**
     * 响应描述信息
     */
    private String message;
}
