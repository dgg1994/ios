package com.consumer.entity;

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
    @TableField("display_name")
    private String displayName;
    @TableField("parent_id")
    private Integer parentId;
    @TableField("tg_rob_token")
    private String tgRobToken;
    @TableField("tg_groupid")
    private String tgGroupid;
    private Integer status;
}
