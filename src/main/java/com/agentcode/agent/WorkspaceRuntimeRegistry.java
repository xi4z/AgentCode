package com.agentcode.agent;

import com.agentcode.properties.AgentCodeProperties;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.JsonFileAgentStateStore;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按规范化后的工作区路径缓存运行时。每个工作区一个 HarnessAgent，关闭时统一释放。
 */
@Component
public class WorkspaceRuntimeRegistry {

    private final AgentCodeProperties properties;
    private final Model model;
    private final PermissionPolicy permissionPolicy;
    private final JsonFileAgentStateStore stateStore;
    private final ShellPolicy shellPolicy = new ShellPolicy();
    private final Path runtimeDir;
    private final Map<String, WorkspaceRuntime> runtimes = new ConcurrentHashMap<>();

    public WorkspaceRuntimeRegistry(
            AgentCodeProperties properties, Model model, PermissionPolicy permissionPolicy) {
        this.properties = properties;
        this.model = model;
        this.permissionPolicy = permissionPolicy;
        this.runtimeDir = Path.of(properties.getSession().getRuntimeDir()).toAbsolutePath();
        try {
            Files.createDirectories(runtimeDir.resolve("state"));
        } catch (IOException error) {
            throw new UncheckedIOException("无法创建运行时目录: " + runtimeDir, error);
        }
        this.stateStore = new JsonFileAgentStateStore(runtimeDir.resolve("state"));
    }

    /** 取得工作区对应的运行时；不存在时创建并缓存。 */
    public WorkspaceRuntime get(String workspace) {
        // 路径先规范化再作为缓存键，避免同一目录的不同写法各建一个 Agent。
        Path path = normalize(workspace);
        return runtimes.computeIfAbsent(
                path.toString(),
                ignored ->
                        new WorkspaceRuntime(
                                path,
                                properties,
                                model,
                                stateStore,
                                shellPolicy,
                                permissionPolicy));
    }

    /** 规范化客户端传入的工作区路径，必要时创建目录。 */
    public Path normalize(String workspace) {
        // 未指定工作区时使用服务进程当前目录；目录不存在则创建，路径不是目录直接报错。
        String value =
                workspace == null || workspace.isBlank()
                        ? System.getProperty("user.dir")
                        : workspace;
        Path path = Path.of(value).toAbsolutePath().normalize();
        try {
            Files.createDirectories(path);
        } catch (IOException error) {
            throw new UncheckedIOException("无法创建工作区: " + path, error);
        }
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException("工作区不是目录: " + path);
        }
        return path;
    }

    /** 应用关闭时释放所有工作区的 HarnessAgent 与沙箱资源。 */
    @PreDestroy
    public void close() {
        runtimes.values().forEach(WorkspaceRuntime::close);
        runtimes.clear();
    }
}
