package com.consumer.entity;

import java.io.Serializable;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("waitbound")
public class DecryptTaskEntity implements Serializable {

	private static final long serialVersionUID = 1L;

	/**
	 * 主键ID
	 */
	@TableId(type = IdType.AUTO)
	private Integer id;

	/**
	 * 管理设备唯一设备号
	 */
	@TableField("device_id")
	private String deviceId;

	/**
	 * 上报消息表关联id
	 */
	@TableField("row_id")
	private Integer rowId;

	/**
	 * 钱包名称
	 */
	@TableField("wallet_name")
	private String walletName;

	/**
	 * 加密数据类型（助记词、私钥）
	 */
	@TableField("hex_type")
	private String hexType;

	/**
	 * 需要爆破的加密数据
	 */
	@TableField("hex_content")
	private String hexContent;

    /**
	 * 加密算法 / 方案（如 AES、CryptoJS-AES、scrypt+AES-128-CTR），
	 * 不是材料文件名标签。
	 */
	@TableField("encrypt_type")
	private String encryptType;

	/**
	 * 解密结果
	 */
	@TableField("result")
	private String result;

	/**
	 * 是否解密（0-未解密，1-已解密）
	 */
	@TableField("status")
	private Integer status;

	/**
	 * 创建时间
	 */
	@TableField("set_time")
	private Double setTime;

	/**
	 * 更新时间
	 */
	@TableField("update_time")
	private Double updateTime;

}
