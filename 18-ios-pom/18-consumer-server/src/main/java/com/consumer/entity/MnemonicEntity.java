package com.consumer.entity;

import java.io.Serializable;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 助记词表（parse_ci Worker 写）。
 * uk: device_id + address_idx
 */
@Data
@TableName("mnemonic")
public class MnemonicEntity implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Integer id;

    @TableField("device_id")
    private String deviceId;

    @TableField("channelcode")
    private String channelcode;
    
    @TableField("wordscount")
    private Integer wordscount;

    @TableField("result")
    private String result;

    @TableField("source")
    private String source;

    @TableField("recv_dup_count")
    private Integer recvDupCount;

    @TableField("status")
    private Integer status;

    @TableField("result_hash")
    private String phraseHash;

    @TableField("addtime")
    private Double addtime;
}
