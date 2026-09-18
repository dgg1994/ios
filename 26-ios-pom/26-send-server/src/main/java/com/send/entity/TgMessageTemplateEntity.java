package com.send.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("tg_message_template")
public class TgMessageTemplateEntity {

    @TableId(type = IdType.AUTO)
    private Integer id;

    private String code;

    private String name;

    private String body;

    private String variables;

    private Integer status;
}
