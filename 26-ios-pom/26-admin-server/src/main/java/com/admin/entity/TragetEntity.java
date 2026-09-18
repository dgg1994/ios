package com.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("traget")
public class TragetEntity {
    @TableId(type = IdType.AUTO)
    private Integer id;
    @TableField("appName")
    private String appName;
    @TableField("bundleId")
    private String bundleId;
    private Integer status;
    private String paths;
}
