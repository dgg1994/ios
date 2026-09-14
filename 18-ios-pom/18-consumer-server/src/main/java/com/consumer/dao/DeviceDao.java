package com.consumer.dao;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.springframework.stereotype.Repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.consumer.entity.DeviceEntity;

import java.util.List;

@Repository
public interface DeviceDao extends BaseMapper<DeviceEntity> {

    @Select("SELECT * FROM device WHERE device_id = #{deviceId} OR deviceid = #{deviceId} ORDER BY id DESC LIMIT 1")
    DeviceEntity findByDeviceIdLatest(@Param("deviceId") String deviceId);

    @Select("SELECT * FROM device WHERE deviceid = #{deviceid} OR device_id = #{deviceid} ORDER BY id DESC LIMIT 1")
    DeviceEntity findByDeviceid(@Param("deviceid") String deviceid);

    @Update("UPDATE device SET onlinestatus=#{onlinestatus}, last_event_at=#{lastEventAt}, c2_series=#{c2Series}, ip=#{ip} " +
            "WHERE id=#{id}")
    int touch(@Param("id") Integer id,
              @Param("onlinestatus") Integer onlinestatus,
              @Param("lastEventAt") Double lastEventAt,
              @Param("c2Series") Integer c2Series,
              @Param("ip") String ip);

    @Select("SELECT * FROM device WHERE device_id = #{deviceId}")
    List<DeviceEntity> findAllByDeviceId(@Param("deviceId") String deviceId);

    @Update("UPDATE device SET onlinestatus=1, last_event_at=#{now}, c2_series=1 " +
            "WHERE device_id=#{deviceId}")
    int touchByDeviceId(@Param("deviceId") String deviceId, @Param("now") double now);
}
