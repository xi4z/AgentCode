package com.agentcode.agent;

import com.agentcode.common.ShellParseHelper;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 工作区 shell 工具。命令在宿主进程执行，审批策略见 {@link ShellPolicy}。
 *
 * <p>工具只负责执行与参数校验；是否放行由 {@link #checkPermissions} 与权限引擎决定。
 */
public final class ShellTool extends ToolBase {

    public static final String NAME = "shell";
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int MAX_OUTPUT_CHARS = 100_000;

    private final Path workspace;
    private final ShellPolicy policy;

    public ShellTool(Path workspace, ShellPolicy policy) {
        super(
                ToolBase.builder()
                        .name(NAME)
                        .description(
                                "Execute a shell command inside the workspace. Returns combined"
                                        + " output and the exit code. Each call starts a new shell,"
                                        + " so working directory and environment changes are scoped"
                                        + " to that call.")
                        .inputSchema(inputSchema())
                        .readOnly(false)
                        .concurrencySafe(false));
        this.workspace = workspace;
        this.policy = policy;
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(
            Map<String, Object> toolInput, PermissionContextState context) {
        String command = stringValue(toolInput.get("command"));
        if (policy.autoApprovable(command)) {
            return Mono.just(PermissionDecision.allow("安全命令自动放行"));
        }
        // 交给权限引擎继续匹配 allow 规则（例如本会话 APPROVE_ALL 写入的规则），
        // 没有命中时引擎会落到默认 ASK。
        return Mono.just(PermissionDecision.passthrough("需要人工审批"));
    }

    @Override
    public boolean matchRule(String ruleContent, Map<String, Object> toolInput) {
        if (ruleContent == null) {
            return true;
        }
        // 规则内容可以是精确命令，也可以是 * / ? 通配符；APPROVE_ALL 写入的是精确命令。
        String command = stringValue(toolInput.get("command"));
        if (ruleContent.equals(command)) {
            return true;
        }
        return ShellParseHelper.compileShellWildcard(ruleContent).matcher(command).matches();
    }

    @Override
    public List<PermissionRule> generateSuggestions(Map<String, Object> toolInput) {
        String command = stringValue(toolInput.get("command"));
        return List.of(
                new PermissionRule(
                        NAME, command == null || command.isBlank() ? null : command,
                        PermissionBehavior.ALLOW, "suggested"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        // 进程等待与流读取都是阻塞操作，放到 boundedElastic，避免阻塞模型事件循环。
        return Mono.fromCallable(() -> execute(param.getInput()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private ToolResultBlock execute(Map<String, Object> input) {
        String command = stringValue(input.get("command"));
        if (command == null || command.isBlank()) {
            return ToolResultBlock.error("command 不能为空");
        }
        int timeoutSeconds = timeoutSeconds(input.get("timeout"));
        Path workingDirectory;
        try {
            workingDirectory =
                    policy.resolveWorkingDirectory(
                            workspace, stringValue(input.get("working_directory")));
        } catch (IllegalArgumentException error) {
            return ToolResultBlock.error(error.getMessage());
        }

        ProcessBuilder builder =
                isWindows()
                        ? new ProcessBuilder("cmd.exe", "/d", "/s", "/c", command)
                        : new ProcessBuilder("/bin/sh", "-c", command);
        builder.directory(workingDirectory.toFile());
        builder.redirectErrorStream(true);
        builder.environment().put("AGENTCODE_WORKSPACE", workspace.toString());

        Process process;
        try {
            process = builder.start();
        } catch (IOException error) {
            return ToolResultBlock.error("命令启动失败: " + error.getMessage());
        }

        CompletableFuture<String> output =
                CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                // 输出必须在等待进程结束前并发读取，否则大输出会填满管道导致死锁。
                                return new String(
                                        process.getInputStream().readAllBytes(),
                                        StandardCharsets.UTF_8);
                            } catch (IOException error) {
                                return "";
                            }
                        });

        boolean finished;
        try {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                // 超时后强制结束进程组；输出线程最多再等 2 秒，避免读取端一直挂住。
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return ToolResultBlock.error("命令执行被中断");
        }

        String text;
        try {
            text = output.get(finished ? 5 : 2, TimeUnit.SECONDS);
        } catch (Exception error) {
            text = "";
        }

        StringBuilder result = new StringBuilder();
        if (!finished) {
            result.append("Timed out after ").append(timeoutSeconds).append("s\n");
        }
        result.append("Exit code: ").append(finished ? process.exitValue() : -1).append('\n');
        if (text != null && !text.isBlank()) {
            result.append('\n').append(truncate(text));
        }
        boolean success = finished && process.exitValue() == 0;
        return ToolResultBlock.text(result.toString())
                .withState(success ? ToolResultState.SUCCESS : ToolResultState.ERROR);
    }

    private static int timeoutSeconds(Object value) {
        if (value instanceof Number number) {
            int seconds = number.intValue();
            return seconds > 0 ? Math.min(seconds, 600) : DEFAULT_TIMEOUT_SECONDS;
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                int seconds = Integer.parseInt(text.strip());
                return seconds > 0 ? Math.min(seconds, 600) : DEFAULT_TIMEOUT_SECONDS;
            } catch (NumberFormatException ignored) {
                return DEFAULT_TIMEOUT_SECONDS;
            }
        }
        return DEFAULT_TIMEOUT_SECONDS;
    }

    private static String truncate(String text) {
        if (text.length() <= MAX_OUTPUT_CHARS) {
            return text;
        }
        return text.substring(0, MAX_OUTPUT_CHARS) + "\n(output was truncated)";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Map<String, Object> inputSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(
                "command",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "Command in the host shell syntax (cmd.exe on Windows, sh elsewhere)."));
        properties.put(
                "working_directory",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "Optional working directory relative to the workspace."));
        properties.put(
                "timeout",
                Map.of(
                        "type",
                        "integer",
                        "description",
                        "Optional timeout in seconds. Default 30, maximum 600."));
        return Map.of(
                "type",
                "object",
                "properties",
                properties,
                "required",
                List.of("command"));
    }
}
