package com.admin.auth;

import java.util.Collections;
import java.util.Set;

import com.admin.entity.AdminUserEntity;

import lombok.Getter;

@Getter
public class AdminContext {

    private final AdminUserEntity user;
    private final String roleCode;
    private final Set<String> permissions;

    public AdminContext(AdminUserEntity user, String roleCode, Set<String> permissions) {
        this.user = user;
        this.roleCode = roleCode == null ? "" : roleCode;
        this.permissions = permissions == null ? Collections.emptySet() : permissions;
    }

    public boolean isSuperadmin() {
        Integer roleId = user.getRoleId();
        return (roleId != null && roleId == 1) || "superadmin".equalsIgnoreCase(roleCode);
    }

    public boolean can(String code) {
        if (isSuperadmin()) {
            return true;
        }
        return code != null && permissions.contains(code);
    }
}
