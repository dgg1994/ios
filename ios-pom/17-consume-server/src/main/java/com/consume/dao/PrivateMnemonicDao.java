package com.consume.dao;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.springframework.stereotype.Repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.consume.entity.PrivateMnemonicEntity;

@Repository
public interface PrivateMnemonicDao extends BaseMapper<PrivateMnemonicEntity> {

    /** 幂等：同设备 + result_hash 已在隐私表则不再插入 */
    @Select("SELECT id FROM private_mnemonic WHERE device_id = #{deviceId} "
            + "AND result_hash = #{resultHash} LIMIT 1")
    Integer findIdByDeviceResultHash(@Param("deviceId") String deviceId,
                                     @Param("resultHash") String resultHash);
}
