package com.consumer.entity;

import java.io.Serializable;

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
	
	@TableField("device_id")
	private String deviceId;
	
	@TableField("lhu")
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

}
