package com.send.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("appid")
public class AppidEntity {

    @TableId(type = IdType.AUTO)
    private Integer id;

    private String appname;

    /** 所属渠道账号 admin_user.id */
    private Integer userid;

    /** 渠道码，与 devices.appId 对应 */
    private String appid;

    private Integer status;
}
