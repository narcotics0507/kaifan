package com.scaffold.common.config;

import com.scaffold.common.exception.BusinessException;
import com.scaffold.common.result.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class RestaurantFeatureWebConfig implements WebMvcConfigurer {
    private final RestaurantFeatures features;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                String path = request.getRequestURI().substring(request.getContextPath().length());
                if (features.isDisabledApi(path)) {
                    throw new BusinessException(ResultCode.PARAM_ERROR, "本店已停用此功能");
                }
                return true;
            }
        }).addPathPatterns("/**");
    }
}
