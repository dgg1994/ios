package com.send.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("traget")
public class TragetEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("appName")
    private String appName;

    @TableField("bundleId")
    private String bundleId;

    /** JSON 数组字符串或逗号分隔 */
    private String paths;

    private Integer status;
}
