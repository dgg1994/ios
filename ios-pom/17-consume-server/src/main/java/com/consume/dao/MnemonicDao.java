package com.consume.dao;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.consume.entity.MnemonicEntity;

public interface MnemonicDao extends BaseMapper<MnemonicEntity> {

    /** 重复助记词：递增 recv_dup_count */
    @Update("UPDATE mnemonic SET recv_dup_count = recv_dup_count + 1 WHERE device_id = #{deviceId} AND result_hash = #{phraseHash}")
    int incrementDupCount(@Param("deviceId") String deviceId, @Param("phraseHash") String phraseHash);
}
