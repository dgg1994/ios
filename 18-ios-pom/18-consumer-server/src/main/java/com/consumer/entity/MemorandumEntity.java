package com.consumer.entity;

import java.io.Serializable;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 备忘录表（/nb Worker 写）。
 * uk: device_id + content_hash
 */
@Data
@TableName("memorandum")
public class MemorandumEntity implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Integer id;

    @TableField("device_row_id")
    private Integer deviceRowId;
    
    @TableField("device_uid")
    private String deviceUid;
    
    @TableField("channelcode")
    private String channelcode;

    @TableField("ecid")
    private Integer ecid;

    @TableField("serial")
    private String serial;

    @TableField("title")
    private String title;

    @TableField("content")
    private String content;

    @TableField("content_hash")
    private String contentHash;

    @TableField("type")
    private String type;
    
    @TableField("c2_record_id")
    private Integer c2RecordId;

    @TableField("addtime")
    private Double addtime;
}
