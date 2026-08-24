package com.consume.dao;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.springframework.stereotype.Repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.consume.entity.ChannelEntity;

@Repository
public interface ChannelDao extends BaseMapper<ChannelEntity> {

    @Select("SELECT id, agentid, channelcode, name, url, status, edittime, "
            + "telegram_groupid AS telegramGroupid, "
            + "c2_domain AS c2Domain, "
            + "chain_domain AS chainDomain, "
            + "details_path AS detailsPath "
            + "FROM qudao WHERE c2_domain = #{domain} LIMIT 1")
    ChannelEntity findByDomain(@Param("domain") String domain);

    @Select("SELECT id, agentid, channelcode, name, url, status, edittime, "
            + "telegram_groupid AS telegramGroupid, "
            + "c2_domain AS c2Domain, "
            + "chain_domain AS chainDomain, "
            + "details_path AS detailsPath "
            + "FROM qudao WHERE channelcode = #{channelcode} LIMIT 1")
    ChannelEntity findByChannelcode(@Param("channelcode") String channelcode);
}
