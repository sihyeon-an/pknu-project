package com.example.pknu_backend.config; // 패키지명 확인 (예: com.example.pknu_backend.config)

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration // 이 클래스가 스프링 설정 클래스임을 명시
public class WebConfig implements WebMvcConfigurer {

    @Value("${file.upload-dir}")
    private String uploadDir; // application.properties의 file.upload-dir 값을 주입받음

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // '/uploads/**' 로 시작하는 모든 요청을
        // 'file:/Users/ash/pknuvue-images/' 경로에서 찾도록 매핑합니다.
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:" + uploadDir + "/"); // 'file:' 접두사와 마지막 '/' 필수
    }
}