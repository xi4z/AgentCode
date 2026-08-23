package com.agentcode.utils;

import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

@Component // 1. 交给Spring管理
public class SpringContextUtil implements ApplicationContextAware {

    private static ApplicationContext context;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        // 2. Spring启动时会自动调用此方法，注入容器引用
        SpringContextUtil.context = applicationContext;
    }

    // 3. 提供静态方法供非Bean类获取Bean
    public static <T> T getBean(Class<T> beanClass) {
        return context.getBean(beanClass);
    }

    public static Object getBean(String beanName) {
        return context.getBean(beanName);
    }
}