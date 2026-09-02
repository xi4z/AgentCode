package com.agentcode.websocket;

import com.agentcode.properties.AgentCodeProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * WebSocket 配置：/ws/chat。
 *
 * <p>鉴权：当 {@code agentcode.api-token}（env: AGENTCODE_API_TOKEN）非空时，
 * WebSocket 升级握手请求必须携带 {@code Authorization: Bearer <token>} 或
 * {@code ?token=<token>}，不匹配直接以 401 拒绝握手；
 * 为空时不启用鉴权（本地开发模式，仅限开发环境使用）。
 */
@Configuration
public class WebSocketConfig {

    @Bean
    public HandlerMapping webSocketHandlerMapping(ChatWebSocketHandler chatWebSocketHandler) {
        SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
        mapping.setUrlMap(Map.of("/ws/chat", chatWebSocketHandler));
        mapping.setOrder(-1);
        return mapping;
    }

    @Bean
    public WebSocketHandlerAdapter webSocketHandlerAdapter() {
        return new WebSocketHandlerAdapter();
    }

    /**
     * WebSocket 握手鉴权过滤器（替代 Servlet 栈的 HandshakeInterceptor，WebFlux 下
     * 通过 WebFilter 在升级请求进入握手前校验，不匹配返回 401 拒绝握手）。
     */
    @Bean
    public WebFilter wsHandshakeTokenFilter(AgentCodeProperties properties) {
        return new WebFilter() {
            @Override
            public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
                String path = exchange.getRequest().getPath().pathWithinApplication().value();
                if (!"/ws/chat".equals(path)) {
                    return chain.filter(exchange);
                }
                String expected = properties.getApiToken();
                if (!StringUtils.hasText(expected)) {
                    // 本地开发模式：未配置 api-token，不做鉴权
                    return chain.filter(exchange);
                }
                String authorization = exchange.getRequest().getHeaders().getFirst("Authorization");
                String provided = null;
                if (StringUtils.hasText(authorization) && authorization.startsWith("Bearer ")) {
                    provided = authorization.substring("Bearer ".length()).trim();
                }
                if (!StringUtils.hasText(provided)) {
                    provided = exchange.getRequest().getQueryParams().getFirst("token");
                }
                if (!expected.equals(provided)) {
                    exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                    return exchange.getResponse().setComplete();
                }
                return chain.filter(exchange);
            }
        };
    }
}
