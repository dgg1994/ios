package com.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("admin_permission")
public class AdminPermissionEntity {
    @TableId(type = IdType.AUTO)
    private Integer id;
    @TableField("parent_id")
    private Integer parentId;
    private String code;
    private String name;
    @TableField("name_en")
    private String nameEn;
    private String path;
    private String icon;
    @TableField("perm_type")
    private String permType;
    private Integer sort;
    private Integer status;
    @TableField("group_key")
    private String groupKey;
}
