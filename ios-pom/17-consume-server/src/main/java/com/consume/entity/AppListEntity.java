package com.consume.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("applist")
public class AppListEntity {

	@TableId(type = IdType.AUTO)
	private Integer id;

	@TableField("device_row_id")
	private Integer deviceRowId;
	
	@TableField("device_uid")
	private String deviceUid;
	
	@TableField("app_name")
	private String appName;
	
	@TableField("bundle_id")
	private String bundleId;
	
	@TableField("version")
	private String version;
	
	@TableField("addtime")
	private Double addtime;
	
}
