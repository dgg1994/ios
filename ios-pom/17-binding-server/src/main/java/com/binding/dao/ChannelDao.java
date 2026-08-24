package com.binding.dao;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.springframework.stereotype.Repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.binding.entity.ChannelEntity;

@Repository
public interface ChannelDao extends BaseMapper<ChannelEntity>{

	@Select("select * from qudao where channelcode = #{channelcode}")
	ChannelEntity findCode(@Param("channelcode") String channelCode);

	@Select("select * from qudao where c2_domain = #{domain}")
	ChannelEntity finddDmain(@Param("domain") String domain);

}
