package com.agentcode.dto;

import com.agentcode.common.ShellParseHelper;
import com.agentcode.properties.AgentCodeProperties;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Shell 命令审批策略：deny → outside-cwd 强制人工 → allow → 默认人工。
 *
 * <p>与 Python 分支 {@code core/permissions/policy.py} 对齐，但名单可由
 * {@code agentcode.agent.approval.*} 配置覆盖：
 * <ul>
 *   <li>{@code safe-commands} / {@code dangerous-commands} / {@code outside-cwd-patterns}
 *       显式配置后<b>整体替换</b>内置默认（白/黑名单语义下"追加"更容易误开放行面）</li>
 *   <li>{@code allow-patterns} / {@code deny-patterns} 是 shell 通配符（{@code git *} 之类），
 *       对整条子命令做匹配，纯追加</li>
 * </ul>
 */
public final class ApprovalPolicy {

    /** 内置安全命令名单：命中且未越界时自动放行 */
    public static final Set<String> DEFAULT_SAFE_COMMANDS = Set.copyOf(ShellParseHelper.safeCommands);

    /** 内置危险命令黑名单：永远需要人工审批 */
    public static final Set<String> DEFAULT_DANGEROUS_COMMANDS = Set.copyOf(ShellParseHelper.DANGEROUS_COMMANDS);

    /** 检测命令是否操作 cwd 之外路径的启发式规则（强制人工审批，不可被 allow 名单绕过） */
    public static final List<String> DEFAULT_OUTSIDE_CWD_PATTERNS = List.of(
            "(^|\\s)/[^\\s]",              // absolute path
            "(^|\\s)~",                    // tilde home
            "(^|\\s)\\.\\.(/|$|\\s)",      // parent traversal
            "\\$\\{?HOME\\b",              // $HOME variable
            "\\$\\{?PWD\\b",               // $PWD variable
            "(^|\\s|;|&&|\\|\\|)cd(\\s|$)", // explicit cd
            "(^|\\s)[0-9&]*>{1,2}\\s*/[^\\s]",        // 重定向到绝对路径（含 2>、&> 等 fd 前缀）
            "(^|\\s)[0-9&]*>{1,2}\\s*~",              // 重定向到 home 目录
            "(^|\\s)[0-9&]*>{1,2}\\s*\\.\\.(/|\\s|$)" // 重定向越界到上级目录
    );

    private final Set<String> safeCommands;
    private final Set<String> dangerousCommands;
    private final List<Pattern> outsideCwdPatterns;
    private final List<Pattern> allowPatterns;
    private final List<Pattern> denyPatterns;

    private ApprovalPolicy(Set<String> safeCommands, Set<String> dangerousCommands,
                           List<Pattern> outsideCwdPatterns,
                           List<Pattern> allowPatterns, List<Pattern> denyPatterns) {
        this.safeCommands = Set.copyOf(safeCommands);
        this.dangerousCommands = Set.copyOf(dangerousCommands);
        this.outsideCwdPatterns = List.copyOf(outsideCwdPatterns);
        this.allowPatterns = List.copyOf(allowPatterns);
        this.denyPatterns = List.copyOf(denyPatterns);
    }

    /** 内置默认策略（不读配置），用于未装配属性的场景与单测 */
    public static ApprovalPolicy defaults() {
        return new ApprovalPolicy(DEFAULT_SAFE_COMMANDS, DEFAULT_DANGEROUS_COMMANDS,
                compileRegex(DEFAULT_OUTSIDE_CWD_PATTERNS), List.of(), List.of());
    }

    /**
     * 由 {@code agentcode.agent.approval.*} 构建策略；未配置的项沿用内置默认。
     */
    public static ApprovalPolicy from(AgentCodeProperties.Approval config) {
        if (config == null) {
            return defaults();
        }
        Set<String> safe = isBlank(config.getSafeCommands())
                ? DEFAULT_SAFE_COMMANDS : new LinkedHashSet<>(config.getSafeCommands());
        Set<String> dangerous = isBlank(config.getDangerousCommands())
                ? DEFAULT_DANGEROUS_COMMANDS : new LinkedHashSet<>(config.getDangerousCommands());
        List<Pattern> outsideCwd = isBlank(config.getOutsideCwdPatterns())
                ? compileRegex(DEFAULT_OUTSIDE_CWD_PATTERNS) : compileRegex(config.getOutsideCwdPatterns());
        List<Pattern> allow = compileShellWildcards(config.getAllowPatterns());
        List<Pattern> deny = compileShellWildcards(config.getDenyPatterns());
        return new ApprovalPolicy(safe, dangerous, outsideCwd, allow, deny);
    }

    /**
     * 单条子命令的策略评估。
     *
     * @return true 表示可自动放行，false 表示需要人工审批
     */
    public boolean autoApprovesSegment(String segment) {
        if (segment == null || segment.isBlank()) {
            return false;
        }
        String trimmed = segment.trim();

        // 1. deny_patterns：命中黑名单一律人工审批
        if (matchesAnyFull(denyPatterns, trimmed)) {
            return false;
        }

        // 1.5 命令替换检测已上移到 autoApproves()：splitShellSegments→splitCommand 会剥离引号，
        //     在段落（无引号）上检测会把单引号字面量（bash 不执行替换）误判为替换；
        //     原始命令字符串上才能正确识别引号语义

        List<String> tokens = ShellParseHelper.splitCommand(trimmed);
        if (tokens.isEmpty()) {
            return false;
        }
        String commandName = tokens.get(0);
        if (dangerousCommands.contains(commandName)) {
            return false;
        }

        // 2. outside-cwd 强制人工，不允许被安全名单绕过
        boolean outsideCwd = matchesAny(outsideCwdPatterns, trimmed);

        // 3. allow_patterns：显式配置的通配符白名单（越界命令即使命中也不放行）
        if (matchesAnyFull(allowPatterns, trimmed)) {
            return !outsideCwd;
        }
        if (outsideCwd) {
            return false;
        }

        // 4. 安全命令名单；默认 ASK
        return safeCommands.contains(commandName);
    }

    /** 整条命令（含复合命令）是否可自动放行 */
    public boolean autoApproves(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        // 命令替换（`` ` `` / $( / ${）在执行期运行任意子命令，静态判定覆盖不了，强制人工。
        // 必须在原始字符串上检测：splitShellSegments 会剥离引号，导致单引号字面量被误判
        if (ShellParseHelper.containsCommandSubstitution(command)) {
            return false;
        }
        List<String> segments = ShellParseHelper.splitShellSegments(command);
        // 纯运算符命令（如 ";"）拆不出任何子命令，按人工审批兜底处理
        if (segments.isEmpty()) {
            return false;
        }
        for (String segment : segments) {
            if (!autoApprovesSegment(segment)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 整条命令是否命中"硬拒绝"（deny 通配符 / 危险命令黑名单）。
     *
     * <p>用于限制会话级放行的作用域：用户点过一次 APPROVE_ALL 不应该让后续危险命令静默执行。
     * 注意这里<b>不含</b> outside-cwd 启发式——它只是"默认询问"的理由，
     * 用户对某条越界命令（如 {@code cat /etc/hosts}）明确一律批准后应当生效。
     */
    public boolean isDenied(String command) {
        if (command == null || command.isBlank()) {
            return true;
        }
        for (String segment : ShellParseHelper.splitShellSegments(command)) {
            if (isDeniedSegment(segment)) {
                return true;
            }
        }
        return false;
    }

    private boolean isDeniedSegment(String segment) {
        if (segment == null || segment.isBlank()) {
            return true;
        }
        String trimmed = segment.trim();
        if (matchesAnyFull(denyPatterns, trimmed)) {
            return true;
        }
        List<String> tokens = ShellParseHelper.splitCommand(trimmed);
        return tokens.isEmpty() || dangerousCommands.contains(tokens.get(0));
    }

    public Set<String> safeCommands() {
        return safeCommands;
    }

    public Set<String> dangerousCommands() {
        return dangerousCommands;
    }

    private static boolean isBlank(List<String> values) {
        return values == null || values.isEmpty();
    }

    private static boolean matchesAny(List<Pattern> patterns, String value) {
        for (Pattern pattern : patterns) {
            if (pattern.matcher(value).find()) {
                return true;
            }
        }
        return false;
    }

    /** 白/黑名单按整条子命令匹配，避免 "echo git status" 这类被误命中 */
    private static boolean matchesAnyFull(List<Pattern> patterns, String value) {
        for (Pattern pattern : patterns) {
            if (pattern.matcher(value).matches()) {
                return true;
            }
        }
        return false;
    }

    private static List<Pattern> compileRegex(List<String> regexes) {
        List<Pattern> compiled = new ArrayList<>();
        for (String regex : regexes) {
            if (regex != null && !regex.isBlank()) {
                compiled.add(Pattern.compile(regex));
            }
        }
        return compiled;
    }

    private static List<Pattern> compileShellWildcards(List<String> wildcards) {
        List<Pattern> compiled = new ArrayList<>();
        if (wildcards == null) {
            return compiled;
        }
        for (String wildcard : wildcards) {
            if (wildcard != null && !wildcard.isBlank()) {
                compiled.add(ShellParseHelper.compileShellWildcard(wildcard.trim()));
            }
        }
        return compiled;
    }
}
