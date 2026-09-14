package com.device.dao;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.springframework.stereotype.Repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.device.entity.DeviceEntity;

@Repository
public interface DeviceDao extends BaseMapper<DeviceEntity> {

	@Select("select * from device where device_id = #{uuid} OR deviceid = #{uuid} order by id desc limit 1")
	DeviceEntity findByDeviceId(@Param("uuid") String uuid);

	@Select("select * from device where device_id = #{uuid} OR deviceid = #{uuid} order by id desc limit 1")
	DeviceEntity findByDeviceIdLatest(@Param("uuid") String uuid);

	@Select("select * from device where deviceid = #{deviceid} OR device_id = #{deviceid} limit 1")
	DeviceEntity findByDeviceid(@Param("deviceid") String deviceid);

	@Update("update device set onlinestatus = 1, ip = #{ip}, last_event_at = #{lastEventAt}, c2_series = 1 "
			+ "where device_id = #{deviceId}")
	int updateOnlineStatus(@Param("deviceId") String deviceId, @Param("ip") String ip,
			@Param("lastEventAt") Double lastEventAt);

}
