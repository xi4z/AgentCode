package com.agentcode.context.block;

public interface ContextBlock {
    // TODO 可能要做权限校验
    enum AllowedRoles{
        USER, //用户
        ASSISTANT,
        BOTH // 两者允许
    }

    default AllowedRoles getAllowedRoles(){
        return AllowedRoles.BOTH;
    }
}
