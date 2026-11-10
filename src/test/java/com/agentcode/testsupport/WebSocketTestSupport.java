package com.agentcode.testsupport;

import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 测试用 WebSocket 客户端：连接、发送、按类型收集消息。
 */
public final class WebSocketTestSupport implements AutoCloseable {

    private final AtomicReference<WebSocketSession> session = new AtomicReference<>();
    private final BlockingQueue<String> inbound = new LinkedBlockingQueue<>();
    private final Disposable connection;

    private WebSocketTestSupport(int port) {
        ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient();
        URI uri = URI.create("ws://localhost:" + port + "/ws/chat");
        this.connection =
                client.execute(
                                uri,
                                ws -> {
                                    session.set(ws);
                                    return ws.receive()
                                            .map(WebSocketMessage::getPayloadAsText)
                                            .doOnNext(inbound::offer)
                                            .then();
                                })
                        .subscribe();
    }

    public static WebSocketTestSupport connect(int port) {
        WebSocketTestSupport client = new WebSocketTestSupport(port);
        client.awaitSession();
        return client;
    }

    public void send(String json) {
        session.get().send(Mono.just(session.get().textMessage(json))).block(Duration.ofSeconds(5));
    }

    public List<String> collectUntil(String marker, Duration timeout) {
        List<String> messages = new ArrayList<>();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            String message;
            try {
                message = inbound.poll(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待 WebSocket 消息被中断", error);
            }
            if (message == null) {
                continue;
            }
            messages.add(message);
            if (message.contains(marker)) {
                return messages;
            }
        }
        throw new AssertionError("未在 " + timeout + " 内收到 " + marker + "，已收到: " + messages);
    }

    private void awaitSession() {
        long deadline = System.currentTimeMillis() + 10_000;
        while (session.get() == null && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(error);
            }
        }
        if (session.get() == null) {
            throw new IllegalStateException("WebSocket 未连接");
        }
    }

    @Override
    public void close() {
        WebSocketSession current = session.getAndSet(null);
        if (current != null) {
            current.close().block(Duration.ofSeconds(5));
        }
        connection.dispose();
    }
}
