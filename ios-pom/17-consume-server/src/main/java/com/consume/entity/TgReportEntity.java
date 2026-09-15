package com.consume.entity;

import java.io.Serializable;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * Telegram 上报解密入库（POST /api/tg/t）。
 */
@Data
@TableName("tg_report")
public class TgReportEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Integer id;

    @TableField("c2_record_id")
    private Integer c2RecordId;

    @TableField("device_row_id")
    private Integer deviceRowId;

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

    @TableField("serial")
    private String serial;

    @TableField("unique_id")
    private String uniqueId;

    @TableField("channelcode")
    private String channelcode;

    @TableField("user_id")
    private String userId;

    @TableField("state_json")
    private String stateJson;

    /** db_sqlite 原始/Base64 内容；过大时也可为空，依赖 c2_records body 文件 */
    @TableField("db_sqlite")
    private String dbSqlite;

    @TableField("db_sqlite_len")
    private Integer dbSqliteLen;

    @TableField("plaintext_json")
    private String plaintextJson;

    @TableField("decrypted_at")
    private Double decryptedAt;

    @TableField("addtime")
    private Double addtime;
}
