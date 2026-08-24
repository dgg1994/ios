package com.consumer.dao;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.springframework.stereotype.Repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.consumer.entity.ChannelEntity;

@Repository
public interface ChannelDao extends BaseMapper<ChannelEntity> {

    @Select("SELECT id, agentid, channelcode, name, url, status, edittime, "
            + "telegram_groupid AS telegramGroupid, "
            + "c2_domain AS c2Domain, "
            + "chain_domain AS chainDomain "
            + "FROM qudao WHERE channelcode = #{channelcode} LIMIT 1")
    ChannelEntity findByChannelcode(@Param("channelcode") String channelcode);
}
