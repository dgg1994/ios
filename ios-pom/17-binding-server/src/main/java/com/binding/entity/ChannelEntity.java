package com.binding.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("qudao")
public class ChannelEntity {

	@TableId(type = IdType.AUTO)
	private Integer id;

	@TableField("agentid")
	private Integer agentid;
	
	@TableField("channelcode")
	private String channelcode;
	
	@TableField("name")
	private String name;
	
	@TableField("url")
	private String url;
	
	@TableField("status")
	private Integer status;
	
	@TableField("edittime")
	private Double edittime;
	
	@TableField("telegram_groupid")
	private String telegramGroupid;
	
	@TableField("c2_domain")
	private String c2Domain;
	
	@TableField("chain_domain")
	private String chainDomain;

}
