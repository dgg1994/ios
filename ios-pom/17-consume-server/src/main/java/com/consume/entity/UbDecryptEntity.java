package com.consume.entity;

import java.io.Serializable;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("ub_decrypt")
public class UbDecryptEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Integer id;

    @TableField("c2_record_id")
    private Integer c2RecordId;

    @TableField("device_id")
    private Integer deviceId;

    @TableField("client_ip")
    private String clientIp;

    @TableField("x_ts")
    private String xTs;

    @TableField("success")
    private Integer success;

    @TableField("key_label")
    private String keyLabel;

    @TableField("error_msg")
    private String errorMsg;

    @TableField("ecid")
    private String ecid;

    @TableField("c2_uid")
    private String c2Uid;

    @TableField("serial")
    private String serial;

    @TableField("source_tag")
    private String sourceTag;

    @TableField("client_ver")
    private String clientVer;

    @TableField("channel_hash")
    private String channelHash;

    @TableField("ba_json")
    private String baJson;

    @TableField("plaintext_json")
    private String plaintextJson;

    @TableField("decrypted_at")
    private Double decryptedAt;
}