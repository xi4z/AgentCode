package com.agentcode.session;

import com.agentcode.exception.SessionChangeStatusException;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@NoArgsConstructor
public class SessionStatus {

    @Getter
    private Status currStatus = Status.FREE;
    private final static Map<Status, Set<Status>> TRANSITIONS = new HashMap<>(); // 状态机转换类

    static {
        TRANSITIONS.put(
                Status.FREE, // 需要转换的状态
                Set.of(Status.RUNNING) // 允许转换的状态
        );

        TRANSITIONS.put(
                Status.RUNNING,
                Set.of(Status.FREE, Status.INTERRUPTED)
        );

        TRANSITIONS.put(
                Status.INTERRUPTED,
                Set.of(Status.RUNNING)
        );
    }


    public enum Status{
        FREE, // 当前会话没有在运行
        RUNNING, // 当前会话正在运行
        INTERRUPTED // 当前会话被 Agent 主动中断
    }

    // 状态机切换
    public synchronized boolean statusChange(Status targetStatus){
        // 先检查状态是否重复
        if (currStatus == targetStatus){
            throw new SessionChangeStatusException("状态" + currStatus + " 重复");
        }

        if (!TRANSITIONS.get(currStatus).contains(targetStatus)) {
            throw new SessionChangeStatusException("状态 " + currStatus + "不能转换为" + targetStatus);
        }

        currStatus = targetStatus;

        return true;
    }
}
