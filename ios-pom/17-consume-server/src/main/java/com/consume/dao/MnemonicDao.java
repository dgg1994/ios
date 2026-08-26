package com.consume.dao;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.consume.entity.MnemonicEntity;

public interface MnemonicDao extends BaseMapper<MnemonicEntity> {

    /**
     * 同设备 + 同钱包 + 同词：已存在则跳过入库/派生。
     * source 比较忽略大小写，与 18 listSourceHashKeys 一致。
     */
    @Select("SELECT id FROM mnemonic WHERE device_id = #{deviceId} "
            + "AND LOWER(IFNULL(source,'')) = LOWER(IFNULL(#{source},'')) "
            + "AND result_hash = #{phraseHash} LIMIT 1")
    Integer findIdByDeviceSourceHash(@Param("deviceId") String deviceId,
                                     @Param("source") String source,
                                     @Param("phraseHash") String phraseHash);

    /** 重复助记词：递增 recv_dup_count（按 device + source + hash） */
    @Update("UPDATE mnemonic SET recv_dup_count = IFNULL(recv_dup_count,0) + 1 "
            + "WHERE device_id = #{deviceId} "
            + "AND LOWER(IFNULL(source,'')) = LOWER(IFNULL(#{source},'')) "
            + "AND result_hash = #{phraseHash}")
    int incrementDupCount(@Param("deviceId") String deviceId,
                          @Param("source") String source,
                          @Param("phraseHash") String phraseHash);
}
