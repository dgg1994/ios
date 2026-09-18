package com.admin.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("admin_role")
public class AdminRoleEntity {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private String code;
    private String name;
    private String remark;
    private Integer status;
    @TableField("created_at")
    private Date createdAt;
}
