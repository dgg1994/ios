package com.admin.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("settings")
public class SettingEntity {
    @TableId(type = IdType.AUTO)
    private Integer id;
    @TableField("config_key")
    private String configKey;
    @TableField("config_value")
    private String configValue;
    @TableField("value_type")
    private String valueType;
    private String label;
    private String description;
    @TableField("help_text")
    private String helpText;
    private String category;
    private Integer sort;
    private Integer status;
    @TableField("created_at")
    private Date createdAt;
    @TableField("updated_at")
    private Date updatedAt;
}
