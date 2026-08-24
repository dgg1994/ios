package com.consume.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("c2_event_decrypt")
public class C2EventEecryptEntity {
	
	@TableId(type = IdType.AUTO)
	private Integer id;

	@TableField("c2_record_id")
	private Integer  c2RecordId;
	
	@TableField("x_ts")
	private String  xTs;
	
	@TableField("success")
	private Integer  success;
	
	@TableField("key_label")
	private String  keyLabel;
	
	@TableField("prefix")
	private String  prefix;
	
	@TableField("plaintext_json")
	private String  plaintextJson;
	
	@TableField("raw_text")
	private String  rawText;
	
	@TableField("error_msg")
	private String  errormsg;
	
	@TableField("decrypted_at")
	private Double  decryptedAt;
	
}
