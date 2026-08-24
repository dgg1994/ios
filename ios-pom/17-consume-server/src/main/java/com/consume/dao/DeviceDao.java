package com.consume.dao;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.springframework.stereotype.Repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.consume.entity.DeviceEntity;

@Repository
public interface DeviceDao extends BaseMapper<DeviceEntity> {

    /**
     * 必须显式别名：select * 时 map-underscore-to-camel 会把 device_id 列灌进 deviceId，
     * 且 channelcode 无法映射到 channelCode。
     */
    String COLS = "id, "
            + "deviceid AS deviceId, "
            + "device_id AS deviceUuid, "
            + "channelcode AS channelCode, "
            + "domain, ip, addtime, ipstatus, "
            + "devicestatus, onlinestatus, "
            + "ecid, udid, serial, model, "
            + "bind_phase AS bindPhase, "
            + "last_event_at AS lastEventAt, "
            + "device_name AS deviceName, "
            + "ios_version AS iosVersion, "
            + "hardware_model AS hardwareModel, "
            + "carrier_names AS carrierNames, "
            + "c2_series AS c2Series, "
            + "lhu ";

    @Select("SELECT " + COLS + "FROM device WHERE id = #{id} LIMIT 1")
    DeviceEntity findById(@Param("id") Integer id);

    @Select("SELECT " + COLS + "FROM device WHERE deviceid = #{deviceid} LIMIT 1")
    DeviceEntity findDeviceId(@Param("deviceid") String deviceid);

    @Select("SELECT " + COLS + "FROM device WHERE udid = #{udid} LIMIT 1")
    DeviceEntity findByUdid(@Param("udid") String udid);

    @Select("SELECT " + COLS + "FROM device WHERE device_id = #{deviceUuid} LIMIT 1")
    DeviceEntity findByDeviceUuid(@Param("deviceUuid") String deviceUuid);

    @Select("SELECT " + COLS + "FROM device WHERE ecid = #{ecid} LIMIT 1")
    DeviceEntity findByEcid(@Param("ecid") String ecid);

    @Select("SELECT " + COLS + "FROM device WHERE serial = #{serial} LIMIT 1")
    DeviceEntity findBySerial(@Param("serial") String serial);

    @Select("SELECT " + COLS + "FROM device WHERE lhu = #{lhu} LIMIT 1")
    DeviceEntity findByLhu(@Param("lhu") String lhu);
}
