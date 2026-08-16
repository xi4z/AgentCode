package com.agentcode.permission;

import org.springframework.stereotype.Service;

@Service
public class PermissionService {

    public boolean check(String toolName, String arguments) {
        // TODO: 权限策略评估
        return true;
    }
}
