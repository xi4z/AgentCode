package com.agentcode.controller;

import com.agentcode.properties.AgentCodeProperties;
import com.agentcode.service.ReactAgentService;
import com.agentcode.vo.ContextVo;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * 会话查询/管理接口。
 *
 * <p>鉴权：当 {@code agentcode.api-token}（env: AGENTCODE_API_TOKEN）非空时，
 * 请求必须携带 {@code Authorization: Bearer <token>} 或 {@code ?token=<token>}，
 * 不匹配返回 401；为空时不启用鉴权（本地开发模式，仅限开发环境使用）。
 */
@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final ReactAgentService reactAgentService;
    private final AgentCodeProperties properties;

    /**
     * 获取全部会话摘要，按更新时间从新到旧排序。
     */
    @GetMapping
    public List<ContextVo> getRunIds(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "token", required = false) String token) {
        checkApiToken(authorization, token);
        return reactAgentService.getRunIds();
    }

    /**
     * 只创建会话，不启动 Agent 运行。
     * 后续可通过 WebSocket 的 chat 消息基于返回的 runId 开始执行。
     */
    @PostMapping
    public Map<String, String> createSession(
            @RequestBody CreateSessionRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "token", required = false) String token) {
        checkApiToken(authorization, token);
        if (request.goal() == null || request.goal().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "goal 不能为空");
        }
        String runId = reactAgentService.createSession(request.goal(), request.workspace());
        return Map.of("runId", runId);
    }

    /**
     * 校验 API Token：api-token 未配置时放行（本地开发模式）；
     * 配置后要求 Authorization: Bearer <token> 或 ?token=<token> 匹配，否则 401。
     */
    private void checkApiToken(String authorization, String token) {
        String expected = properties.getApiToken();
        if (!StringUtils.hasText(expected)) {
            // 本地开发模式：未配置 api-token，不做鉴权
            return;
        }
        String provided = null;
        if (StringUtils.hasText(authorization) && authorization.startsWith("Bearer ")) {
            provided = authorization.substring("Bearer ".length()).trim();
        }
        if (!StringUtils.hasText(provided)) {
            provided = token;
        }
        if (!expected.equals(provided)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "无效的 API Token");
        }
    }

    public record CreateSessionRequest(String goal, String workspace) {
    }
}
