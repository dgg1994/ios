package com.consumer.dao;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.springframework.stereotype.Repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.consumer.entity.MnemonicEntity;

@Repository
public interface MnemonicDao extends BaseMapper<MnemonicEntity> {

    @Select("SELECT * FROM mnemonic WHERE device_id = #{deviceId} ORDER BY id DESC LIMIT 1")
    MnemonicEntity findByDeviceId(@Param("deviceId") String deviceId);

    /** 异步补写幂等检查：同 device + source + result_hash 已存在则不重复入库 */
    @Select("SELECT id FROM mnemonic WHERE device_id = #{deviceId} AND source = #{source} AND result_hash = #{phraseHash} LIMIT 1")
    Integer findIdByDeviceSourceHash(@Param("deviceId") String deviceId,
                                     @Param("source") String source,
                                     @Param("phraseHash") String phraseHash);
}
