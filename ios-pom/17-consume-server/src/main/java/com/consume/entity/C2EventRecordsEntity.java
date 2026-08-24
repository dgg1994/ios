package com.consume.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("c2_event_records")
public class C2EventRecordsEntity {
	
	@TableId(type = IdType.AUTO)
	private Integer id;

	@TableField("c2_record_id")
	private Integer c2RecordId;
	
	@TableField("device_id")
	private Integer deviceId;
	
	@TableField("channelcode")
	private String channelcode;
	
	@TableField("et")
	private String et;
	
	@TableField("pv")
	private String pv;
	
	@TableField("pn")
	private String pn;
	
	@TableField("m")
	private String m;
	
	@TableField("ecid")
	private String ecid;
	
	@TableField("serial")
	private String serial;
	
	@TableField("c2_uid")
	private String c2Uid;
	
	@TableField("event_uuid")
	private String eventUuid;
	
	@TableField("lhu")
	private String lhu;
	
	@TableField("sbu")
	private String sbu;
	
	@TableField("tm")
	private Long tm;
	
	@TableField("ex")
	private Integer ex;
	
	@TableField("desc_text")
	private String descText;
	
	@TableField("ctx_json")
	private String ctxJson;
	
	@TableField("plaintext_json")
	private String plaintextJson;
	
	@TableField("captured_at")
	private Double capturedAt;

}
