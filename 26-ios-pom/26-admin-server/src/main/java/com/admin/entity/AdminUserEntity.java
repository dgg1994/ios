package com.admin.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("admin_user")
public class AdminUserEntity {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private String username;
    @TableField("password_hash")
    private String passwordHash;
    @TableField("display_name")
    private String displayName;
    @TableField("role_id")
    private Integer roleId;
    private Integer status;
    @TableField("last_login_at")
    private Date lastLoginAt;
    @TableField("created_at")
    private Date createdAt;
    @TableField("updated_at")
    private Date updatedAt;
    @TableField("parent_id")
    private Integer parentId;
    @TableField("tg_rob_token")
    private String tgRobToken;
    private String tgGroupid;
    @TableField("token_version")
    private Integer tokenVersion;
    private String collectaddress;
    @TableField("login_ip_whitelist")
    private String loginIpWhitelist;
}
