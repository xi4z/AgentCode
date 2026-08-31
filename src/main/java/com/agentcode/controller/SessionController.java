package com.agentcode.controller;

import com.agentcode.service.ReactAgentService;
import com.agentcode.vo.ContextVo;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * 会话查询/管理接口。
 */
@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final ReactAgentService reactAgentService;

    /**
     * 获取全部会话摘要，按更新时间从新到旧排序。
     */
    @GetMapping
    public List<ContextVo> getRunIds() {
        return reactAgentService.getRunIds();
    }

    /**
     * 只创建会话，不启动 Agent 运行。
     * 后续可通过 WebSocket 的 chat 消息基于返回的 runId 开始执行。
     */
    @PostMapping
    public Map<String, String> createSession(@RequestBody CreateSessionRequest request) {
        if (request.goal() == null || request.goal().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "goal 不能为空");
        }
        String runId = reactAgentService.createSession(request.goal(), request.workspace());
        return Map.of("runId", runId);
    }

    public record CreateSessionRequest(String goal, String workspace) {
    }
}