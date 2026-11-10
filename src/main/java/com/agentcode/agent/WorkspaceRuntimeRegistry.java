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

    public WorkspaceRuntime get(String workspace) {
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

    public Path normalize(String workspace) {
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

    @PreDestroy
    public void close() {
        runtimes.values().forEach(WorkspaceRuntime::close);
        runtimes.clear();
    }
}
