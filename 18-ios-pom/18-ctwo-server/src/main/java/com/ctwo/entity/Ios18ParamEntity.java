package com.ctwo.entity;

import java.io.Serializable;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("ios18param")
public class Ios18ParamEntity implements Serializable {

	private static final long serialVersionUID = 1L;

	@TableId(type = IdType.AUTO)
	private Integer id;

	@TableField("kind")
	private String kind;
	
	@TableField("method")
	private String method;
	
	@TableField("client_ip")
	private String clientIp;
	
	@TableField("path")
	private String path;
	
	@TableField("headers")
	private String headers;
	
	@TableField("body")
	private String body;
	
	@TableField("remark")
	private String remark;
	
	@TableField(value = "device_id", strategy = FieldStrategy.IGNORED)
	private String deviceId;

	@TableField(value = "lhu", strategy = FieldStrategy.IGNORED)
	private String lhu;
	
	@TableField("category")
	private String category;
	
	@TableField("category_dir")
	private String categoryDir;
	
	@TableField("body_bytes")
	private Integer bodyBytes;
	
	@TableField("storage")
	private String storage;
	
	@TableField("file_path")
	private String filePath;
	
	@TableField("unpack_path")
	private String unpackPath;
	
	@TableField("ci_result")
	private String ciResult;
	
	@TableField("tt")
	private String tt;
	
	@TableField("source")
	private String source;
	
	@TableField("seq")
	private Long seq;
	
	@TableField("unimplemented")
	private Integer unimplemented;
	
	@TableField("addtime")
	private Double addtime;

	/**
	 * 通用 capture 实体工厂：填充所有接口共用的默认字段，
	 * 调用方再按需设置 kind/category/categoryDir/body/bodyBytes/storage/filePath 等。
	 *
	 * @param kind      归一 kind（u/nb/war/...）
	 * @param method    HTTP 方法（GET/POST）
	 * @param clientIp  客户端 IP
	 * @param path      请求 URI
	 * @param headers   headers JSON
	 * @param deviceUuid 设备 UUID（缺失兜底空串，确保 NOT NULL 列不报错）
	 * @param tt        body 中 tt 字段（缺失空串）
	 * @param source    body 中 source 字段（缺失空串）
	 */
	public static Ios18ParamEntity newCapture(String kind, String method, String clientIp, String path,
											 String headers, String deviceUuid, String tt, String source) {
		Ios18ParamEntity e = new Ios18ParamEntity();
		e.kind = kind;
		e.method = method;
		e.clientIp = clientIp;
		e.path = path;
		e.headers = headers;
		e.deviceId = deviceUuid == null ? "" : deviceUuid;
		e.lhu = deviceUuid == null ? "" : deviceUuid;
		e.tt = tt == null ? "" : tt;
		e.source = source == null ? "" : source;
		e.remark = "";
		e.unpackPath = "";
		e.ciResult = "";
		e.seq = 0L;
		e.unimplemented = 0;
		e.addtime = System.currentTimeMillis() / 1000.0;
		return e;
	}

}
