//package com.report.config;
//
//import org.springframework.context.annotation.Configuration;
//import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
//import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
//
///**
// * 静态资源映射：把 /details/** 映射到 classpath:/details/
// * 访问 https://host/details/show.html → classpath:/details/show.html
// *
// * 注意：与 src/main/resources/details/ 配合
// *       静态资源 handler 优先级低于 @RequestMapping，API 路由不受影响
// */
//@Configuration
//public class WebConfig implements WebMvcConfigurer {
//
//    @Override
//    public void addResourceHandlers(ResourceHandlerRegistry registry) {
//        registry.addResourceHandler("/details/**")
//                .addResourceLocations("classpath:/details/");
//    }
//}


//package com.report.config;
//
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.context.annotation.Configuration;
//import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
//import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
//
///**
// * 静态资源映射：把 /details/** 映射到磁盘路径
// * 访问 https://host/details/show.html → 磁盘路径/show.html
// */
//@Configuration
//public class WebConfig implements WebMvcConfigurer {
//
//    @Value("${file.storage.path:/data/details/}")
//    private String storagePath;
//
//    @Override
//    public void addResourceHandlers(ResourceHandlerRegistry registry) {
//        registry.addResourceHandler("/details/**")
//                .addResourceLocations("file:" + storagePath);
//    }
//}