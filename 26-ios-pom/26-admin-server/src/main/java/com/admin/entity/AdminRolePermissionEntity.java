package com.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("admin_role_permission")
public class AdminRolePermissionEntity {
    @TableId(type = IdType.AUTO)
    private Integer id;
    @TableField("role_id")
    private Integer roleId;
    @TableField("permission_id")
    private Integer permissionId;
}
