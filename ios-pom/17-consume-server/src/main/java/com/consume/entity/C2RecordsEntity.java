package com.consume.entity;

import java.io.Serializable;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * c2_records 实体（与 binding/report-server 对齐，用于按 id 加载记录并回填 device_id）。
 */

@Data
@TableName("c2_records")
public class C2RecordsEntity implements Serializable{
	
	private static final long serialVersionUID = 1L;

	/**
	 * 主键 ID (bigint)
	 */
	@TableId(type = IdType.AUTO)
	private Integer id;

	/**
	 * 类型 (varchar 32)
	 */
	@TableField("kind")
	private String kind;

	/**
	 * 请求方法 GET/POST (varchar 8)
	 */
	@TableField("method")
	private String method;

	/**
	 * 客户端 IP (varchar 64)
	 */
	@TableField("client_ip")
	private String clientIp;

	/**
	 * 请求路径 (varchar 512)
	 */
	@TableField("path")
	private String path;

	/**
	 * 请求头 (mediumtext)
	 */
	@TableField("headers")
	private String headers;

	/**
	 * 请求体 (mediumtext)
	 */
	@TableField("body")
	private String body;

	/**
	 * 客户端时间戳 x-ts (varchar 32)
	 */
	@TableField("x_ts")
	private String xTs;

	/**
	 * 捕获时间 (double) 注意：与您之前的 DeviceEntity 类似，这是秒级浮点数时间戳
	 */
	@TableField("captured_at")
	private Double capturedAt;

	/**
	 * 关联的设备 ID (bigint)
	 */
	@TableField("device_id")
	private Long deviceId;

	/**
	 * 版本号 (varchar 8)
	 */
	@TableField("version")
	private String version;

	/**
	 * headers_path (varchar 512)
	 */
	@TableField("headers_path")
	private String headersPath;

	/**
	 * body_path (varchar 512)
	 */
	@TableField("body_path")
	private String bodyPath;
}
