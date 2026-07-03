package com.lifetips.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Agentic-LifeTips 启动入口。
 * scanBasePackages = "com.lifetips" 确保跨模块扫描到所有 Bean。
 *
 * @author PCRao
 */
@SpringBootApplication(scanBasePackages = "com.lifetips")
public class TipsApplication {

    public static void main(String[] args) {
        SpringApplication.run(TipsApplication.class, args);
    }
}
