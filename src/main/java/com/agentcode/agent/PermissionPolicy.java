package com.agentcode.agent;

import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 权限上下文：只读工具直接放行，shell 由 {@link ShellTool} 自行判断，写入类工具默认询问。
 */
@Component
public class PermissionPolicy {

    private static final List<String> READ_ONLY_TOOLS =
            List.of(
                    "read_file",
                    "grep_files",
                    "glob_files",
                    "list_files",
                    "memory_search",
                    "memory_get",
                    "session_search",
                    "todo_write");

    /**
     * 构造每个会话初始使用的权限上下文。
     *
     * <p>只要存在 allow 规则，权限引擎就不会走 trivial 快速路径；未显式放行的工具会落到默认
     * ASK，由前端展示审批。
     */
    public PermissionContextState context() {
        PermissionContextState.Builder builder =
                PermissionContextState.builder().mode(PermissionMode.DEFAULT);
        for (String tool : READ_ONLY_TOOLS) {
            builder.addAllowRule(
                    tool, new PermissionRule(tool, null, PermissionBehavior.ALLOW, "agentcode"));
        }
        return builder.build();
    }
}
