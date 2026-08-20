package com.agentcode.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Shell 工具
 */
public final class ShellParseHelper {
    public static final Set<String> safeCommands = new HashSet<String>(
            List.of(
                    "pwd",
                    "ls",
                    "cat",
                    "echo",
                    "date",
                    "whoami",
                    "hostname",
                    "id",
                    "uname"
            )
    );

    public static final Set<String> DANGEROUS_COMMANDS = Set.of(
            "rm",
            "rmdir",
            "mkfs",
            "dd",
            "shutdown",
            "reboot",
            "poweroff",
            "halt",
            "kill",
            "killall",
            "pkill",
            "chmod",
            "chown"
    );

    /**
     * 按照可能的情况拆分 Command 方便做筛查
     * @param input
     * @return
     */
    public static List<String> splitCommand(String input) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        boolean singleQuote = false;
        boolean doubleQuote = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            // 单引号
            if (c == '\'' && !doubleQuote) {
                singleQuote = !singleQuote;
                continue;
            }

            // 双引号
            if (c == '"' && !singleQuote) {
                doubleQuote = !doubleQuote;
                continue;
            }

            // 引号外的空格：结束当前 token
            if (Character.isWhitespace(c)
                    && !singleQuote
                    && !doubleQuote) {

                if (!current.isEmpty()) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }

                continue;
            }

            // && / ||
            if (!singleQuote && !doubleQuote) {
                if (input.startsWith("&&", i)
                        || input.startsWith("||", i)) {

                    if (!current.isEmpty()) {
                        tokens.add(current.toString());
                        current.setLength(0);
                    }

                    tokens.add(input.substring(i, i + 2));
                    i++;
                    continue;
                }

                // | ;
                if (c == '|' || c == ';') {

                    if (!current.isEmpty()) {
                        tokens.add(current.toString());
                        current.setLength(0);
                    }

                    tokens.add(String.valueOf(c));
                    continue;
                }
            }

            current.append(c);
        }

        if (!current.isEmpty()) {
            tokens.add(current.toString());
        }

        return tokens;
    }

    /**
     * 从工具参数 JSON 中解析出 shell command
     */
    public static String extractShellCommand(String arguments) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(arguments);
            return root.path("command").asText();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 将 shell 通配符转成正则表达式
     */
    public static Pattern compileShellWildcard(String pattern) {
        String regex = pattern
                .replace(".", "\\.")
                .replace("*", ".*")
                .replace("?", ".");
        return Pattern.compile(regex);
    }

    /**
     * 将命令按 shell 运算符拆成多个子命令片段
     */
    public static List<String> splitShellSegments(String command) {
        List<String> tokens = ShellParseHelper.splitCommand(command);
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String token : tokens) {
            if (isShellOperator(token)) {
                if (!current.isEmpty()) {
                    segments.add(current.toString());
                    current.setLength(0);
                }
            } else {
                if (!current.isEmpty()) {
                    current.append(' ');
                }
                current.append(token);
            }
        }
        if (!current.isEmpty()) {
            segments.add(current.toString());
        }
        return segments;
    }

    /**
     * 判断 token 是否为 shell 运算符
     */
    public static boolean isShellOperator(String token) {
        return token.equals("&&") || token.equals("||")
                || token.equals("|") || token.equals(";");
    }
}
