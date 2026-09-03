package com.agentcode.memory;

import com.agentcode.properties.AgentCodeProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * {@link MemoryStore} 的文件实现：目录布局、索引预算、关键词检索与原子落盘都在这里。
 *
 * <p>并发：parallelToolExecution 下 memory_write / memory_forget 可能并发触发，索引是
 * read-modify-write，全部变更走本实例单锁；文件写入用 tmp + 原子 move，读侧不会看到半截文件。
 *
 * <p>审计日志沿用 AUDIT_MEMORY_* 约定：事件名大写、字段 k=v、正文经 brief() 单行化截断。
 */
@Slf4j
@Component
public class FileMemoryStore implements MemoryStore {

    /** 每层索引预算：200 行或 25KB（先到为准）。超限只提醒修剪，不静默丢数据。 */
    private static final int INDEX_MAX_LINES = 200;
    private static final int INDEX_MAX_BYTES = 25 * 1024;

    private static final String INDEX_FILE_NAME = "MEMORY.md";
    private static final int SEARCH_MAX_FILES = 8;
    private static final int SEARCH_FILE_CONTENT_CHARS = 1500;
    private static final int SEARCH_TOTAL_BUDGET_CHARS = 8000;
    private static final int SCAN_MAX_FILES_PER_LAYER = 200;
    private static final int NAME_MAX_LENGTH = 64;
    private static final int SUMMARY_MAX_LENGTH = 200;
    private static final int AUDIT_TEXT_MAX_LENGTH = 120;

    private static final String TYPE_USER = "user";
    private static final String TYPE_FEEDBACK = "feedback";
    private static final String TYPE_PROJECT = "project";
    private static final String TYPE_REFERENCE = "reference";

    /** 索引行：- [type] 摘要 → 文件名.md */
    private static final Pattern INDEX_LINE =
            Pattern.compile("^-\\s*\\[([^\\]]+)]\\s*(.*?)\\s*\u2192\\s*(\\S+\\.md)\\s*$");
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final Pattern CJK_RUN = Pattern.compile("^[\\u4e00-\\u9fff]+$");
    /** 文件名允许：ascii 字母数字、汉字、连字符；其余折叠为 - */
    private static final Pattern NAME_ILLEGAL = Pattern.compile("[^a-z0-9\\u4e00-\\u9fff]+");

    private static final String BLOCK_HEADER = """
            <auto_memory>
            你拥有跨会话长期记忆，以 markdown 文件存放；下方是索引（每条一行摘要，全文需检索获取）。
            规则：
            - 回忆：需要某条记忆的全文、或索引被标注为已截断时，用 memory_search 检索；查不到就回答不知道，不要编造记忆。
            - 写入：用户表达长期偏好、纠正你的做法、确认项目约定，或出现以后仍会用到的事实时，用 memory_write 记一条。
              只记无法从代码、git 历史或当前会话直接推出的内容；已有语义相近的记忆用同名覆盖合并，不要堆重复条目。
            - 遗忘：记忆被用户否认或已过时用 memory_forget 删除。每层索引预算 200 行 / 25KB，超限必须先合并清理。
            当前索引：
            """;

    private enum Layer {
        GLOBAL("global", "全局"),
        PROJECT("project", "项目");

        private final String key;
        private final String label;

        Layer(String key, String label) {
            this.key = key;
            this.label = label;
        }

        static Layer of(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            String normalized = raw.trim().toLowerCase(Locale.ROOT);
            for (Layer layer : values()) {
                if (layer.key.equals(normalized)) {
                    return layer;
                }
            }
            return null;
        }
    }

    private final AgentCodeProperties properties;

    /** 保护「索引 read-modify-write + 文件写入」的复合操作；读侧靠原子 move 免锁。 */
    private final ReentrantLock writeLock = new ReentrantLock();

    public FileMemoryStore(AgentCodeProperties properties) {
        this.properties = properties;
    }

    // ==================== 读：会话起点索引快照 ====================

    @Override
    public String buildPromptBlock(String workspace) {
        try {
            StringBuilder sb = new StringBuilder(BLOCK_HEADER);
            for (Layer layer : Layer.values()) {
                Path dir = dirOf(layer, workspace);
                sb.append("\n[").append(layer.label).append("层 · ").append(displayDir(dir)).append("]\n");
                if (dir == null) {
                    sb.append("（当前会话没有可用 workspace，本层不可用）\n");
                    continue;
                }
                IndexRead index = readIndexBudgeted(dir);
                if (index.text().isBlank()) {
                    sb.append("（暂无记忆）\n");
                } else {
                    sb.append(index.text()).append('\n');
                    if (index.hiddenEntries() > 0) {
                        sb.append("… 另有 ").append(index.hiddenEntries())
                                .append(" 条超出索引预算未列出，用 memory_search 检索。\n");
                    }
                }
            }
            sb.append("</auto_memory>");
            return sb.toString();
        } catch (Exception e) {
            // fail-soft：记忆读不出来就只丢本会话的记忆块，绝不阻断会话创建
            log.warn("AUDIT_MEMORY_PROMPT_FAILED error={}", e.getMessage(), e);
            return "";
        }
    }

    // ==================== 检索 ====================

    @Override
    public String search(String workspace, String query) {
        if (query == null || query.isBlank()) {
            return "query 不能为空";
        }
        String q = query.trim();
        try {
            List<String> tokens = tokenize(q);
            String phrase = q.toLowerCase(Locale.ROOT);
            List<Scored> hits = new ArrayList<>();
            for (Layer layer : Layer.values()) {
                Path dir = dirOf(layer, workspace);
                hits.addAll(scanLayer(layer, dir, tokens, phrase));
            }
            hits.sort(Comparator.comparingInt(Scored::score).reversed());

            if (hits.isEmpty()) {
                log.info("AUDIT_MEMORY_TOOL_SEARCH query=\"{}\" hits=0", brief(q));
                return "没有找到相关的长期记忆。";
            }
            StringBuilder out = new StringBuilder("长期记忆命中 ").append(hits.size())
                    .append(" 条（最多展示 ").append(SEARCH_MAX_FILES).append(" 条）：\n");
            int shown = 0;
            for (Scored hit : hits) {
                if (shown >= SEARCH_MAX_FILES) {
                    out.append("…（其余 ").append(hits.size() - shown)
                            .append(" 条未展示，可用更精确的关键词再查）\n");
                    break;
                }
                if (out.length() >= SEARCH_TOTAL_BUDGET_CHARS) {
                    out.append("…（结果超出预算已截断）\n");
                    break;
                }
                MemoryFile file = hit.file();
                out.append("\n### [").append(file.type()).append(" · ").append(file.layer().label)
                        .append("] ").append(file.fileName());
                if (!file.modified().isBlank()) {
                    out.append("（更新于 ").append(file.modified()).append("）");
                }
                out.append('\n');
                if (!file.summary().isBlank()) {
                    out.append("摘要：").append(file.summary()).append('\n');
                }
                out.append(truncate(file.body(), SEARCH_FILE_CONTENT_CHARS)).append('\n');
                shown++;
            }
            log.info("AUDIT_MEMORY_TOOL_SEARCH query=\"{}\" hits={} returned={}", brief(q), hits.size(), shown);
            return out.toString().trim();
        } catch (Exception e) {
            log.warn("AUDIT_MEMORY_TOOL_FAILED tool=memory_search query=\"{}\" error={}", brief(q), e.getMessage(), e);
            return "长期记忆查询暂时不可用，请按「没有相关记忆」处理，不要编造。";
        }
    }

    private List<Scored> scanLayer(Layer layer, Path dir, List<String> tokens, String phrase) throws IOException {
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        Map<String, String> indexSummaries = indexSummaries(dir);
        List<Scored> result = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> {
                        String name = p.getFileName().toString();
                        return name.endsWith(".md") && !INDEX_FILE_NAME.equals(name);
                    })
                    .sorted()
                    .limit(SCAN_MAX_FILES_PER_LAYER)
                    .forEach(files::add);
        }
        for (Path file : files) {
            MemoryFile parsed = parseTopicFile(file, layer, indexSummaries.get(file.getFileName().toString()));
            int score = score(parsed, tokens, phrase);
            if (score > 0) {
                result.add(new Scored(parsed, score));
            }
        }
        return result;
    }

    /** 打分：文件名命中最重，摘要次之，正文计数封顶；整句命中额外加权。 */
    private int score(MemoryFile file, List<String> tokens, String phrase) {
        Set<String> nameWords = splitNameWords(file.fileName());
        String summary = file.summary().toLowerCase(Locale.ROOT);
        String body = file.body().toLowerCase(Locale.ROOT);
        int score = 0;
        for (String token : tokens) {
            if (nameWords.contains(token)) {
                score += 4;
            }
            if (!summary.isEmpty() && summary.contains(token)) {
                score += 3;
            }
            score += Math.min(5, countOccurrences(body, token));
        }
        if (score > 0 && !phrase.isBlank() && (summary.contains(phrase) || body.contains(phrase))) {
            score += 6;
        }
        return score;
    }

    // ==================== 写入 ====================

    @Override
    public String write(String workspace, String type, String name, String summary, String content) {
        String normalizedType = normalizeType(type);
        if (normalizedType == null) {
            return "非法 type（收到 \"" + brief(String.valueOf(type)) + "\"）。合法值："
                    + "user / feedback 存全局层（跨项目的用户事实），project / reference 存项目层（本仓库事实与外部资料位置）。";
        }
        if (content == null || content.isBlank()) {
            return "content 不能为空：正文应是面向未来会话的简明事实，一到三句话即可。";
        }
        String fileName = slugify(name) + ".md";
        String oneLineSummary = singleLine(summary, SUMMARY_MAX_LENGTH);
        if (oneLineSummary.isEmpty()) {
            oneLineSummary = singleLine(content, SUMMARY_MAX_LENGTH);
        }
        Layer layer = TYPE_USER.equals(normalizedType) || TYPE_FEEDBACK.equals(normalizedType)
                ? Layer.GLOBAL : Layer.PROJECT;
        Path dir = dirOf(layer, workspace);
        if (dir == null) {
            return "项目层记忆需要有效 workspace；请改用 user / feedback 类型存全局层。";
        }
        try {
            writeLock.lock();
            try {
                Files.createDirectories(dir);
                Path target = dir.resolve(fileName);
                boolean updated = Files.exists(target);
                String fileText = "---\n"
                        + "type: " + normalizedType + "\n"
                        + "name: " + fileName.substring(0, fileName.length() - 3) + "\n"
                        + "summary: " + oneLineSummary + "\n"
                        + "modified: " + LocalDateTime.now() + "\n"
                        + "---\n\n"
                        + content.strip() + "\n";
                atomicWrite(target, fileText);
                int entries = upsertIndexLine(dir, fileName, normalizedType, oneLineSummary);
                boolean overBudget = isIndexOverBudget(dir);
                log.info("AUDIT_MEMORY_WRITE layer={} file={} action={} entries={} overBudget={} summary=\"{}\"",
                        layer.key, fileName, updated ? "UPDATE" : "ADD", entries, overBudget, brief(oneLineSummary));
                String result = (updated ? "已更新 " : "已保存 ")
                        + "[" + normalizedType + "] " + displayDir(dir) + "/" + fileName
                        + "（该层索引共 " + entries + " 条）";
                if (overBudget) {
                    result += "\n⚠ " + INDEX_FILE_NAME + " 已超出 " + INDEX_MAX_LINES + " 行 / "
                            + (INDEX_MAX_BYTES / 1024) + "KB 预算：请用 memory_forget 删除过时记忆，或把近似条目合并成一条（同名覆盖）。";
                }
                return result;
            } finally {
                writeLock.unlock();
            }
        } catch (Exception e) {
            log.warn("AUDIT_MEMORY_WRITE_FAILED layer={} name=\"{}\" error={}", layer.key, brief(name), e.getMessage(), e);
            return "长期记忆写入失败（" + e.getMessage() + "），本轮按未保存处理，可稍后重试。";
        }
    }

    // ==================== 遗忘 ====================

    @Override
    public String forget(String workspace, String scope, String name) {
        if (name == null || name.isBlank()) {
            return "name 不能为空（索引行末尾的文件名，.md 可省略）";
        }
        Layer wanted = null;
        if (scope != null && !scope.isBlank()) {
            wanted = Layer.of(scope);
            if (wanted == null) {
                return "非法 scope（收到 \"" + brief(scope) + "\"）。合法值：global / project，或留空自动查找。";
            }
        }
        String fileName = slugify(name) + ".md";
        try {
            writeLock.lock();
            try {
                List<Object[]> found = new ArrayList<>();
                for (Layer layer : Layer.values()) {
                    if (wanted != null && wanted != layer) {
                        continue;
                    }
                    Path dir = dirOf(layer, workspace);
                    if (dir == null) {
                        continue;
                    }
                    Path target = dir.resolve(fileName);
                    if (Files.exists(target)) {
                        found.add(new Object[]{layer, dir, target});
                    }
                }
                if (found.isEmpty()) {
                    return "未找到记忆 [" + fileName + "]，可先用 memory_search 确认文件名。";
                }
                if (found.size() > 1) {
                    return "两层存在同名记忆 " + fileName + "，请传 scope=global 或 scope=project 指定删除哪一层。";
                }
                Layer layer = (Layer) found.get(0)[0];
                Path dir = (Path) found.get(0)[1];
                Files.delete((Path) found.get(0)[2]);
                int entries = dropIndexLine(dir, fileName);
                log.info("AUDIT_MEMORY_FORGET layer={} file={} entriesLeft={}", layer.key, fileName, entries);
                return "已删除 [" + layer.label + "] " + displayDir(dir) + "/" + fileName
                        + "（该层索引剩 " + entries + " 条）";
            } finally {
                writeLock.unlock();
            }
        } catch (Exception e) {
            log.warn("AUDIT_MEMORY_FORGET_FAILED name=\"{}\" error={}", brief(name), e.getMessage(), e);
            return "长期记忆删除失败（" + e.getMessage() + "），可稍后重试。";
        }
    }

    // ==================== 目录与路径 ====================

    private Path dirOf(Layer layer, String workspace) {
        if (layer == Layer.GLOBAL) {
            return globalDir();
        }
        if (workspace == null || workspace.isBlank()) {
            return null;
        }
        try {
            return Paths.get(workspace).toAbsolutePath().normalize().resolve(".agent").resolve("memory");
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /** 全局记忆根目录：agentcode.agent.memory-dir，默认 ~/.agent/memory。 */
    private Path globalDir() {
        String configured = null;
        if (properties != null && properties.getAgent() != null) {
            configured = properties.getAgent().getMemoryDir();
        }
        if (configured == null || configured.isBlank()) {
            configured = "~/.agent/memory";
        }
        return expandHome(configured.trim());
    }

    private Path expandHome(String file) {
        if (file.startsWith("~/") || file.equals("~")) {
            return Paths.get(System.getProperty("user.home")).resolve(file.substring(Math.min(2, file.length())));
        }
        return Paths.get(file);
    }

    private String displayDir(Path dir) {
        if (dir == null) {
            return "-";
        }
        String home = System.getProperty("user.home");
        String absolute = dir.toAbsolutePath().toString();
        return home != null && absolute.startsWith(home) ? "~" + absolute.substring(home.length()) : absolute;
    }

    // ==================== 索引读写 ====================

    private record IndexRead(String text, int hiddenEntries) {
    }

    /** 读索引前 INDEX_MAX_LINES 行且累计不超 INDEX_MAX_BYTES，返回可见文本与被隐藏条数。 */
    private IndexRead readIndexBudgeted(Path dir) throws IOException {
        Path index = dir.resolve(INDEX_FILE_NAME);
        if (!Files.isRegularFile(index)) {
            return new IndexRead("", 0);
        }
        List<String> entryLines = entryLines(index);
        if (entryLines.isEmpty()) {
            return new IndexRead("", 0);
        }
        StringBuilder visible = new StringBuilder();
        int bytes = 0;
        int shown = 0;
        for (String line : entryLines) {
            int lineBytes = line.getBytes(StandardCharsets.UTF_8).length + 1;
            if (shown >= INDEX_MAX_LINES || bytes + lineBytes > INDEX_MAX_BYTES) {
                break;
            }
            visible.append(line).append('\n');
            bytes += lineBytes;
            shown++;
        }
        return new IndexRead(visible.toString().trim(), entryLines.size() - shown);
    }

    /** 索引里全部条目行（不含说明性文本）。 */
    private List<String> readAllLines(Path index) throws IOException {
        return Files.readAllLines(index, StandardCharsets.UTF_8);
    }

    private List<String> entryLines(Path index) throws IOException {
        List<String> entries = new ArrayList<>();
        for (String line : readAllLines(index)) {
            if (INDEX_LINE.matcher(line).matches()) {
                entries.add(line.strip());
            }
        }
        return entries;
    }

    /** 文件名 → 索引摘要，供缺少 frontmatter 的旧文件/手工文件回退用。 */
    private Map<String, String> indexSummaries(Path dir) {
        Map<String, String> map = new HashMap<>();
        try {
            Path index = dir.resolve(INDEX_FILE_NAME);
            if (!Files.isRegularFile(index)) {
                return map;
            }
            for (String line : entryLines(index)) {
                Matcher matcher = INDEX_LINE.matcher(line);
                if (matcher.matches()) {
                    map.put(matcher.group(3), matcher.group(2).trim());
                }
            }
        } catch (IOException e) {
            log.debug("AUDIT_MEMORY_INDEX_READ_FAILED dir={} error={}", dir, e.getMessage());
        }
        return map;
    }

    /** 按文件名替换或追加索引行，返回条目总数。 */
    private int upsertIndexLine(Path dir, String fileName, String type, String summary) throws IOException {
        Path index = dir.resolve(INDEX_FILE_NAME);
        List<String> lines = Files.isRegularFile(index) ? readAllLines(index) : new ArrayList<>();
        String newLine = "- [" + type + "] " + summary + " \u2192 " + fileName;
        int replaced = -1;
        for (int i = 0; i < lines.size(); i++) {
            Matcher matcher = INDEX_LINE.matcher(lines.get(i).strip());
            if (matcher.matches() && matcher.group(3).equals(fileName)) {
                replaced = i;
                break;
            }
        }
        if (replaced >= 0) {
            lines.set(replaced, newLine);
        } else {
            if (!lines.isEmpty() && !lines.get(lines.size() - 1).isBlank()) {
                lines.add("");
            }
            lines.add(newLine);
        }
        atomicWrite(index, String.join("\n", lines) + "\n");
        int entries = 0;
        for (String line : lines) {
            if (INDEX_LINE.matcher(line.strip()).matches()) {
                entries++;
            }
        }
        return entries;
    }

    /** 删除指定文件名的索引行（连同紧随其后的空行归一），返回剩余条目数。 */
    private int dropIndexLine(Path dir, String fileName) throws IOException {
        Path index = dir.resolve(INDEX_FILE_NAME);
        if (!Files.isRegularFile(index)) {
            return 0;
        }
        List<String> kept = new ArrayList<>();
        for (String line : readAllLines(index)) {
            Matcher matcher = INDEX_LINE.matcher(line.strip());
            if (matcher.matches() && matcher.group(3).equals(fileName)) {
                continue;
            }
            kept.add(line);
        }
        while (!kept.isEmpty() && kept.get(kept.size() - 1).isBlank()) {
            kept.remove(kept.size() - 1);
        }
        atomicWrite(index, kept.isEmpty() ? "" : String.join("\n", kept) + "\n");
        int entries = 0;
        for (String line : kept) {
            if (INDEX_LINE.matcher(line.strip()).matches()) {
                entries++;
            }
        }
        return entries;
    }

    private boolean isIndexOverBudget(Path dir) {
        try {
            Path index = dir.resolve(INDEX_FILE_NAME);
            if (!Files.isRegularFile(index)) {
                return false;
            }
            return entryLines(index).size() > INDEX_MAX_LINES
                    || Files.size(index) > INDEX_MAX_BYTES;
        } catch (IOException e) {
            return false;
        }
    }

    // ==================== 记忆文件解析 ====================

    private record MemoryFile(Layer layer, String fileName, String type, String summary,
                              String modified, String body) {
    }

    private record Scored(MemoryFile file, int score) {
    }

    private MemoryFile parseTopicFile(Path path, Layer layer, String indexSummaryFallback) {
        String fileName = path.getFileName().toString();
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            String type = "note";
            String summary = indexSummaryFallback == null ? "" : indexSummaryFallback;
            String modified = "";
            String body = String.join("\n", lines).strip();
            if (!lines.isEmpty() && lines.get(0).strip().equals("---")) {
                int close = -1;
                for (int i = 1; i < lines.size(); i++) {
                    if (lines.get(i).strip().equals("---")) {
                        close = i;
                        break;
                    }
                }
                if (close > 0) {
                    for (int i = 1; i < close; i++) {
                        int colon = lines.get(i).indexOf(':');
                        if (colon <= 0) {
                            continue;
                        }
                        String key = lines.get(i).substring(0, colon).trim().toLowerCase(Locale.ROOT);
                        String value = lines.get(i).substring(colon + 1).trim();
                        if (value.length() >= 2 && (value.charAt(0) == '"' || value.charAt(0) == '\'')) {
                            value = value.substring(1, value.lastIndexOf(value.charAt(0)) > 0
                                    ? value.lastIndexOf(value.charAt(0)) : value.length());
                        }
                        switch (key) {
                            case "type" -> type = value;
                            case "summary" -> summary = value;
                            case "modified" -> modified = value;
                            default -> { }
                        }
                    }
                    body = String.join("\n", lines.subList(close + 1, lines.size())).strip();
                }
            }
            return new MemoryFile(layer, fileName, type, summary, modified, body);
        } catch (IOException e) {
            return new MemoryFile(layer, fileName, "note", indexSummaryFallback == null ? "" : indexSummaryFallback,
                    "", "（文件读取失败：" + e.getMessage() + "）");
        }
    }

    // ==================== 关键词切分 ====================

    /**
     * 切词：拉丁词按原样成 token；CJK 连续串额外拆二元组
     * （中文查询没有空格，"我平时用什么包管理器" 必须能命中摘要里的 "包管理"）。
     */
    private List<String> tokenize(String query) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = TOKEN_PATTERN.matcher(query.toLowerCase(Locale.ROOT));
        Set<String> seen = new HashSet<>();
        while (matcher.find()) {
            String token = matcher.group();
            if (token.length() >= 2 || CJK_RUN.matcher(token).matches()) {
                addToken(tokens, seen, token);
            }
            if (token.length() > 2 && CJK_RUN.matcher(token).matches()) {
                for (int i = 0; i + 2 <= token.length(); i++) {
                    addToken(tokens, seen, token.substring(i, i + 2));
                }
            }
        }
        return tokens;
    }

    private void addToken(List<String> tokens, Set<String> seen, String token) {
        if (seen.add(token)) {
            tokens.add(token);
        }
    }

    private Set<String> splitNameWords(String fileName) {
        Set<String> words = new HashSet<>();
        String stem = fileName.toLowerCase(Locale.ROOT);
        if (stem.endsWith(".md")) {
            stem = stem.substring(0, stem.length() - 3);
        }
        for (String part : stem.split("[-_.]")) {
            if (!part.isBlank()) {
                words.add(part);
            }
        }
        return words;
    }

    private int countOccurrences(String haystack, String needle) {
        if (needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + needle.length();
        }
    }

    // ==================== 通用工具 ====================

    private String normalizeType(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case TYPE_USER, "preference", "偏好" -> TYPE_USER;
            case TYPE_FEEDBACK, "correction", "纠正" -> TYPE_FEEDBACK;
            case TYPE_PROJECT, "convention", "decision", "约定" -> TYPE_PROJECT;
            case TYPE_REFERENCE, "ref" -> TYPE_REFERENCE;
            default -> null;
        };
    }

    private String slugify(String raw) {
        String name = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (name.endsWith(".md")) {
            name = name.substring(0, name.length() - 3);
        }
        String slug = NAME_ILLEGAL.matcher(name).replaceAll("-")
                .replaceAll("-{2,}", "-").replaceAll("^-|-$", "");
        if (slug.length() > NAME_MAX_LENGTH) {
            slug = slug.substring(0, NAME_MAX_LENGTH).replaceAll("-$", "");
        }
        return slug.isEmpty() ? "memory" : slug;
    }

    private String singleLine(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= maxLength ? oneLine : oneLine.substring(0, maxLength) + "…";
    }

    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text == null ? "" : text;
        }
        return text.substring(0, maxLength) + "…（已截断）";
    }

    private void atomicWrite(Path target, String content) throws IOException {
        Path dir = target.getParent();
        Files.createDirectories(dir);
        Path tmp = dir.resolve(target.getFileName().toString() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 审计日志单行化 + 截断，与全仓 AUDIT_MEMORY_* 约定保持一致。 */
    private String brief(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        if (oneLine.length() <= AUDIT_TEXT_MAX_LENGTH) {
            return oneLine;
        }
        return oneLine.substring(0, AUDIT_TEXT_MAX_LENGTH) + "...(len=" + oneLine.length() + ")";
    }
}
