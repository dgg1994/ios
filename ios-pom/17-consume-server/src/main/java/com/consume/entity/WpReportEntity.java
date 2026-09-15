package com.consume.entity;

import java.io.Serializable;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * WhatsApp 上报解密入库（POST /api/wp/t）。
 */
@Data
@TableName("wp_report")
public class WpReportEntity implements Serializable {

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

    @TableField("api_type")
    private String apiType;

    @TableField("app_version")
    private String appVersion;

    @TableField("user_id")
    private String userId;

    @TableField("phone_id")
    private String phoneId;

    @TableField("nickname")
    private String nickname;

    @TableField("client_static_keypair_b64")
    private String clientStaticKeypairBase64;

    @TableField("phone_keystore_json")
    private String phoneKeystoreJson;

    @TableField("device_config_json")
    private String deviceConfigJson;

    @TableField("device_info_json")
    private String deviceInfoJson;

    @TableField("locale")
    private String locale;

    @TableField("routing_info")
    private String routingInfo;

    @TableField("upload_token_random_bytes")
    private String uploadTokenRandomBytes;

    @TableField("server_static_public_b64")
    private String serverStaticPublicBase64;

    @TableField("proxy")
    private String proxy;

    @TableField("sim_operator")
    private String simOperator;

    @TableField("data_json")
    private String dataJson;

    @TableField("plaintext_json")
    private String plaintextJson;

    @TableField("decrypted_at")
    private Double decryptedAt;

    @TableField("addtime")
    private Double addtime;
}
