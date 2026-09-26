package com.agentcode.memory;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * {@link MemoryStore} 的文件实现。
 *
 * <p>三层落法：全局记忆在配置里（只读）、{@code <workspace>/Agent.md} 只读、
 * {@code <workspace>/.memory/memory.md} 可写。可写层是单个 markdown 文件，一条记忆一个
 * {@code ## name} 小节（紧跟一行 {@code > 摘要}），没有独立索引文件 —— 写入直接改这一份，
 * 检索就是全文关键词匹配，省掉"索引与正文两份真相"的一致性问题。
 *
 * <p>并发：同一实例内所有变更走单锁；落盘用 tmp + 原子 move，读侧不会看到半截文件。
 * 写入路径由实现固定（工作区 + 配置里的相对路径），模型只能给"名字"，给不出路径 —— 越界写不出去。
 */
@Slf4j
public class FileMemoryStore implements MemoryStore {

    private static final String INDEX_TITLE = "# Agent Memory";
    private static final int NAME_MAX = 64;
    private static final int SUMMARY_MAX = 200;
    private static final int PROMPT_BLOCK_MAX_CHARS = 6000;
    private static final int SEARCH_MAX_HITS = 6;
    private static final int SEARCH_SNIPPET_CHARS = 800;
    private static final Pattern SECTION = Pattern.compile("^##\\s+(.+?)\\s*$", Pattern.MULTILINE);
    private static final Pattern SUMMARY_LINE = Pattern.compile("^>\\s*(.+?)\\s*$", Pattern.MULTILINE);
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final Pattern CJK_RUN = Pattern.compile("^[\\u4e00-\\u9fff]+$");
    private static final Pattern ILLEGAL_NAME = Pattern.compile("[^\\p{L}\\p{N}_-]+");

    private final String workspaceMemoryFile;
    private final String memoryFile;
    private final String globalMemory;
    private final ReentrantLock lock = new ReentrantLock();

    public FileMemoryStore(String workspaceMemoryFile, String memoryFile, String globalMemory) {
        this.workspaceMemoryFile = workspaceMemoryFile == null || workspaceMemoryFile.isBlank()
                ? "Agent.md" : workspaceMemoryFile.trim();
        this.memoryFile = memoryFile == null || memoryFile.isBlank() ? ".memory/memory.md" : memoryFile.trim();
        this.globalMemory = globalMemory == null ? "" : globalMemory.strip();
    }

    @Override
    public String writableMemoryPath(String workspace) {
        return memoryPath(workspace).toAbsolutePath().toString();
    }

    @Override
    public String buildPromptBlock(String workspace) {
        try {
            StringBuilder block = new StringBuilder("<memory>\n");
            block.append("你有一套跨会话长期记忆，分三层，权限不同：\n");
            block.append("- 全局记忆：服务端配置下发，只读，你改不了（人也只能改配置）。\n");
            block.append("- 工作区 ").append(workspaceMemoryFile).append("：人写的项目约定，只读，不要试图修改。\n");
            block.append("- 工作区 ").append(memoryFile).append("：你的记忆，可自由增删改（memory_write / memory_forget），")
                    .append("同一工作区的新会话开局即可见。\n\n");

            if (!globalMemory.isBlank()) {
                block.append("## 全局记忆（只读）\n").append(globalMemory).append("\n\n");
            }
            String workspaceMemory = readFileIfExists(workspacePath(workspace));
            if (!workspaceMemory.isBlank()) {
                block.append("## 工作区 ").append(workspaceMemoryFile).append("（只读）\n")
                        .append(workspaceMemory).append("\n\n");
            }
            String own = readFileIfExists(memoryPath(workspace));
            if (!own.isBlank()) {
                block.append("## 你记住的事（").append(memoryFile).append("）\n").append(own).append("\n\n");
            }
            block.append("用法：回忆用 memory_search（跨三层检索，查不到就说不知道，不要编）；")
                    .append("用户表达长期偏好、纠正你的做法、确认项目约定，或出现以后会话仍需要的事实时，用 memory_write 记一条；")
                    .append("记忆被否认或过时用 memory_forget 删除。只记无法从代码与 git 历史直接看出的内容，")
                    .append("重复的用同名覆盖合并。\n</memory>");

            String text = block.toString();
            return text.length() <= PROMPT_BLOCK_MAX_CHARS
                    ? text
                    : text.substring(0, PROMPT_BLOCK_MAX_CHARS) + "\n…（记忆块已截断，用 memory_search 取全文）\n</memory>";
        } catch (Exception e) {
            log.warn("AUDIT_MEMORY_PROMPT_FAILED workspace={} error={}", workspace, e.getMessage());
            return "";
        }
    }

    @Override
    public String search(String workspace, String query) {
        try {
            if (query == null || query.isBlank()) {
                return "记忆检索需要一个关键词。";
            }
            List<String> terms = terms(query);
            List<String> hits = new ArrayList<>();
            collectHits("全局记忆（只读）", globalMemory, terms, hits);
            collectHits("工作区 " + workspaceMemoryFile + "（只读）",
                    readFileIfExists(workspacePath(workspace)), terms, hits);
            collectHits(memoryFile, readFileIfExists(memoryPath(workspace)), terms, hits);
            if (hits.isEmpty()) {
                return "没有找到相关记忆（已检索全局记忆、" + workspaceMemoryFile + "、" + memoryFile + "）。";
            }
            return String.join("\n\n", hits);
        } catch (Exception e) {
            log.warn("AUDIT_MEMORY_SEARCH_FAILED workspace={} error={}", workspace, e.getMessage());
            return "长期记忆检索暂时不可用，按「没有相关记忆」处理，不要编造。";
        }
    }

    @Override
    public String write(String workspace, String name, String summary, String content) {
        String cleanName = cleanName(name);
        if (cleanName.isEmpty()) {
            return "记忆名不合法：用小写字母/数字/连字符或中文，长度 1-" + NAME_MAX + "。";
        }
        if (content == null || content.isBlank()) {
            return "记忆正文为空，未保存。";
        }
        lock.lock();
        try {
            Path path = memoryPath(workspace);
            Map<String, Section> sections = parse(readFileIfExists(path));
            String cleanSummary = summary == null ? "" : summary.strip();
            if (cleanSummary.length() > SUMMARY_MAX) {
                cleanSummary = cleanSummary.substring(0, SUMMARY_MAX);
            }
            boolean replaced = sections.containsKey(cleanName);
            sections.put(cleanName, new Section(cleanName, cleanSummary, content.strip()));
            Files.createDirectories(path.getParent());
            writeAtomic(path, render(sections));
            return (replaced ? "已更新记忆 " : "已记住 ") + cleanName + "（共 " + sections.size() + " 条，落盘 "
                    + memoryFile + "，同一工作区的新会话可见）。";
        } catch (Exception e) {
            log.warn("AUDIT_MEMORY_WRITE_FAILED workspace={} name={} error={}", workspace, cleanName, e.getMessage());
            return "长期记忆写入失败，本轮按未保存处理；不要反复重试。";
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String forget(String workspace, String name) {
        String cleanName = cleanName(name);
        if (cleanName.isEmpty()) {
            return "记忆名不合法。";
        }
        lock.lock();
        try {
            Path path = memoryPath(workspace);
            Map<String, Section> sections = parse(readFileIfExists(path));
            if (sections.remove(cleanName) == null) {
                return "没有名为 " + cleanName + " 的记忆（只读层不可删除）。";
            }
            writeAtomic(path, render(sections));
            return "已删除记忆 " + cleanName + "（剩余 " + sections.size() + " 条）。";
        } catch (Exception e) {
            log.warn("AUDIT_MEMORY_FORGET_FAILED workspace={} name={} error={}", workspace, cleanName, e.getMessage());
            return "长期记忆删除失败，本轮按未删除处理。";
        } finally {
            lock.unlock();
        }
    }

    // ==================== 内部 ====================

    private record Section(String name, String summary, String content) {
    }

    private Map<String, Section> parse(String text) {
        Map<String, Section> sections = new LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            return sections;
        }
        Matcher matcher = SECTION.matcher(text);
        List<int[]> bounds = new ArrayList<>();
        List<String> names = new ArrayList<>();
        while (matcher.find()) {
            bounds.add(new int[]{matcher.start(), matcher.end()});
            names.add(matcher.group(1).strip());
        }
        for (int i = 0; i < bounds.size(); i++) {
            int bodyStart = bounds.get(i)[1];
            int bodyEnd = i + 1 < bounds.size() ? bounds.get(i + 1)[0] : text.length();
            String body = text.substring(bodyStart, bodyEnd).strip();
            String summary = "";
            Matcher summaryMatcher = SUMMARY_LINE.matcher(body);
            if (summaryMatcher.find() && summaryMatcher.start() == 0) {
                summary = summaryMatcher.group(1).strip();
                body = body.substring(summaryMatcher.end()).strip();
            }
            sections.put(names.get(i), new Section(names.get(i), summary, body));
        }
        return sections;
    }

    private String render(Map<String, Section> sections) {
        StringBuilder sb = new StringBuilder(INDEX_TITLE).append("\n\n");
        sections.values().forEach(section -> {
            sb.append("## ").append(section.name()).append("\n");
            if (!section.summary().isBlank()) {
                sb.append("> ").append(section.summary()).append("\n");
            }
            sb.append(section.content()).append("\n\n");
        });
        return sb.toString();
    }

    private void collectHits(String layer, String text, List<String> terms, List<String> hits) {
        if (text == null || text.isBlank() || hits.size() >= SEARCH_MAX_HITS) {
            return;
        }
        Matcher matcher = SECTION.matcher(text);
        List<int[]> bounds = new ArrayList<>();
        List<String> names = new ArrayList<>();
        while (matcher.find()) {
            bounds.add(new int[]{matcher.start(), matcher.end()});
            names.add(matcher.group(1).strip());
        }
        if (bounds.isEmpty()) {
            // 没有小节结构（全局记忆、Agent.md 常见）：整段按行匹配
            if (score(text.toLowerCase(Locale.ROOT), terms) > 0) {
                hits.add("[" + layer + "]\n" + snippet(text));
            }
            return;
        }
        for (int i = 0; i < bounds.size() && hits.size() < SEARCH_MAX_HITS; i++) {
            int end = i + 1 < bounds.size() ? bounds.get(i + 1)[0] : text.length();
            String section = text.substring(bounds.get(i)[0], end);
            if (score(section.toLowerCase(Locale.ROOT), terms) > 0) {
                hits.add("[" + layer + " / " + names.get(i) + "]\n" + snippet(section));
            }
        }
    }

    private int score(String text, List<String> terms) {
        int hits = 0;
        for (String term : terms) {
            if (text.contains(term)) {
                hits++;
            }
        }
        return hits;
    }

    private String snippet(String text) {
        String flat = text.strip();
        return flat.length() <= SEARCH_SNIPPET_CHARS ? flat : flat.substring(0, SEARCH_SNIPPET_CHARS) + "…";
    }

    private List<String> terms(String query) {
        List<String> terms = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(query.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String token = matcher.group();
            if (CJK_RUN.matcher(token).matches() && token.length() > 2) {
                // 中文按 2 字滑窗切，避免整句当一个词
                for (int i = 0; i + 2 <= token.length(); i++) {
                    terms.add(token.substring(i, i + 2));
                }
            } else {
                terms.add(token);
            }
        }
        return terms.stream().distinct().toList();
    }

    private String cleanName(String name) {
        if (name == null) {
            return "";
        }
        String cleaned = ILLEGAL_NAME.matcher(name.strip().toLowerCase(Locale.ROOT)).replaceAll("-");
        cleaned = cleaned.replaceAll("^-+|-+$", "");
        return cleaned.length() > NAME_MAX ? cleaned.substring(0, NAME_MAX) : cleaned;
    }

    private Path workspacePath(String workspace) {
        Path base = workspace == null || workspace.isBlank()
                ? Paths.get(System.getProperty("user.dir"))
                : Paths.get(workspace);
        return base.resolve(workspaceMemoryFile);
    }

    private Path memoryPath(String workspace) {
        Path base = workspace == null || workspace.isBlank()
                ? Paths.get(System.getProperty("user.dir"))
                : Paths.get(workspace);
        return base.resolve(memoryFile);
    }

    private String readFileIfExists(Path path) {
        if (path == null || !Files.exists(path)) {
            return "";
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            log.warn("AUDIT_MEMORY_READ_FAILED path={} error={}", path, e.getMessage());
            return "";
        }
    }

    private void writeAtomic(Path path, String content) throws IOException {
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
