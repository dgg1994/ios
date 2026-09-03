package com.ctwo.dao;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.springframework.stereotype.Repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ctwo.entity.ChannelEntity;

@Repository
public interface ChannelDao extends BaseMapper<ChannelEntity> {

    /**
     * 按 c2_domain 查渠道。host 为归一化裸域名（无 scheme/端口）。
     */
    @Select("SELECT * FROM qudao WHERE "
            + "c2_domain = #{host} "
            + "OR c2_domain = CONCAT('https://', #{host}) "
            + "OR c2_domain = CONCAT('http://', #{host}) "
            + "OR LOWER(REPLACE(REPLACE(REPLACE(IFNULL(c2_domain,''), 'https://', ''), 'http://', ''), '/', '')) = #{host} "
            + "LIMIT 1")
    ChannelEntity findByC2DomainHost(@Param("host") String host);
}
