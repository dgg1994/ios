package com.consume.dao;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.consume.entity.C2RecordsEntity;

public interface C2RecordDao extends BaseMapper<C2RecordsEntity> {

    /**
     * 回填 c2_records.device_id（关联设备主键）。
     */
    @Update("UPDATE c2_records SET device_id = #{deviceId} WHERE id = #{recordId} AND (device_id IS NULL OR device_id = 0)")
    int linkDevice(@Param("recordId") long recordId, @Param("deviceId") long deviceId);
}
