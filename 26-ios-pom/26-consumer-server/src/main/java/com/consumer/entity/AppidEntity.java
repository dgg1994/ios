package com.consumer.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("appid")
public class AppidEntity {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private String appname;
    private Integer userid;
    private String appid;
    private Integer status;
}
