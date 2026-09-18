package com.admin.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("memorandum")
public class MemorandumEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("deviceId")
    private String deviceId;
    @TableField("appId")
    private String appId;
    private Integer userid;
    private String result;
    @TableField("result_hash")
    private String resultHash;
    private Date addtime;
}
