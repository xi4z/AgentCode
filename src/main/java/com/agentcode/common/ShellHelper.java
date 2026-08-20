package com.agentcode.common;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Shell 工具
 */
public final class ShellHelper {
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
}
