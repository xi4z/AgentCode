package com.agentcode.websocket;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

import java.net.URI;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

/**
 * 将根路径重定向到前端静态入口，便于直接访问 http://localhost:8080/。
 */
@Configuration
public class HomeRouter {

    @Bean
    public RouterFunction<ServerResponse> homePageRouter() {
        return route(GET("/"), request ->
                ServerResponse.temporaryRedirect(URI.create("/index.html")).build());
    }
}
