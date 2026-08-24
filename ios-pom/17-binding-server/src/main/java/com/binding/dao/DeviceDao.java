package com.binding.dao;

import java.util.List;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.binding.entity.DeviceEntity;

/**
 * device 表 Mapper。

 */
public interface DeviceDao extends BaseMapper<DeviceEntity> {

    @Select("SELECT id, deviceid, channelcode, domain, ip, addtime, ipstatus, devicestatus, "
            + "onlinestatus, ecid, udid, serial, model, bind_phase, last_event_at, "
            + "device_name, ios_version, hardware_model, carrier_names, c2_series, lhu "
            + "FROM device WHERE deviceid = #{deviceid} LIMIT 1")
    DeviceEntity findDeviceId(@Param("deviceid") String deviceid);

    /**
     * 相似设备查询。
     *
     */
	@Select("SELECT id, deviceid, channelcode, domain, ip, addtime, ipstatus, devicestatus, " +
	        "onlinestatus, ecid, udid, serial, model, bind_phase, last_event_at, " +
	        "device_name, ios_version, hardware_model, carrier_names, c2_series " +
	        "FROM device " +
	        "WHERE bind_phase = 0 " +
	        "  AND ip LIKE CONCAT(#{ipPrefix}, '%') " +
	        "  AND REPLACE(ios_version, 'IOS ', '') LIKE CONCAT(#{versionPrefix}, '%') " +
	        "  AND addtime BETWEEN #{fromTs} AND #{toTs} " +
	        "ORDER BY addtime DESC " +
	        "LIMIT #{maxResults}")
	List<DeviceEntity> findSimilarDevices(
	        @Param("ipPrefix") String ipPrefix,
	        @Param("versionPrefix") String versionPrefix,
	        @Param("fromTs") double fromTs,
	        @Param("toTs") double toTs,
	        @Param("maxResults") int maxResults
	);

    /**
     * 局部字段更新（"最匹配"设备命中后调用）。
     * <p>并发：WHERE 子句带 {@code last_event_at IS NULL OR last_event_at < #{nowSec}}，
     * 多个异步线程并发命中同一条记录时，第二个线程会因 0 rows 而放弃，避免回滚。
     * @param productVersion 
     *
     * @return 受影响行数；0 表示乐观锁失败（并发抢占）
     */
    @Update("UPDATE device SET " +
            "  deviceid      = COALESCE(NULLIF(#{idLike}, ''), deviceid), " +
            "  udid          = COALESCE(NULLIF(#{uValue}, ''), udid), " +
            "  ecid          = COALESCE(NULLIF(#{idLike}, ''), ecid), " +
            "  model         = COALESCE(NULLIF(#{productType}, ''), model), " +
            "  device_name   = COALESCE(NULLIF(#{deviceName}, ''), device_name), " +
            "  device_id   = COALESCE(NULLIF(#{uValue}, ''), device_id), " +
            "  ios_version   = COALESCE(NULLIF(#{productVersion}, ''), ios_version), " +
            "  last_event_at = #{nowSec}, " +
            "  c2_series     = 0, " +
            "  devicestatus  = 1, " +
            "  bind_phase    = 1, " +
            "  onlinestatus    = 1 " +
            "WHERE id = #{id} " +
            "  AND (last_event_at IS NULL OR last_event_at < #{nowSec})")
    int patchBestMatch(
            @Param("id") Integer id,
            @Param("idLike") String idLike,
            @Param("uValue") String uValue,
            @Param("productType") String productType,
            @Param("deviceName") String deviceName,
            @Param("nowSec") double nowSec, 
            @Param("productVersion") String productVersion
    );

    
    @Select("SELECT id, deviceid, channelcode, domain, ip, addtime, ipstatus, devicestatus, " +
	        "onlinestatus, ecid, udid, serial, model, bind_phase, last_event_at, " +
	        "device_name, ios_version, hardware_model, carrier_names, c2_series " +
	        "FROM device " +
	        "WHERE bind_phase = 0 " +
	        "  AND id != #{id} " +
	        "  AND channelcode = #{channelcode} " +
	        "  AND ip LIKE CONCAT(#{ipPrefix}, '%') " +
	        "  AND REPLACE(ios_version, 'IOS ', '') LIKE CONCAT(#{versionPrefix}, '%') " +
	        "  AND addtime BETWEEN #{fromTs} AND #{toTs} " +
	        "ORDER BY addtime DESC " +
	        "LIMIT #{maxResults}")
	List<DeviceEntity> findSimilarDevicesTwo(@Param("ipPrefix") String ipPrefix,
	        @Param("versionPrefix") String versionPrefix,
	        @Param("fromTs") double fromTs,
	        @Param("toTs") double toTs,
	        @Param("maxResults") int maxResults,
	        @Param("channelcode") String channelcode, 
	        @Param("id") Integer id);

    @Delete("delete from device "+
    		"WHERE bind_phase = 0 " +
	        "  AND id != #{id} " +
	        "  AND channelcode = #{channelcode} " +
	        "  AND ip LIKE CONCAT(#{ipPrefix}, '%') " +
	        "  AND addtime BETWEEN #{fromTs} AND #{toTs} " +
	        "ORDER BY addtime DESC " +
	        "LIMIT #{maxResults}")
	void deleteSimilarDevicesTwo(@Param("ipPrefix") String ipPrefix,
	        @Param("fromTs") double fromTs,
	        @Param("toTs") double toTs,
	        @Param("maxResults") int maxResults,
	        @Param("channelcode") String channelcode, 
	        @Param("id") Integer id);

		 // 1. 查询总数（新增）
    @Select("SELECT COUNT(*) FROM device " +
            "WHERE bind_phase = 0 " +
            "  AND channelcode = #{channelcode} " +
            "  AND id != #{id} " +
            "  AND ip LIKE CONCAT(#{ipPrefix}, '%') " +
            "  AND addtime BETWEEN #{fromTs} AND #{toTs}")
    int countSimilarDevices(@Param("ipPrefix") String ipPrefix,
                            @Param("fromTs") double fromTs,
                            @Param("toTs") double toTs,
                            @Param("channelcode") String channelcode,
                            @Param("id") Integer id);

    /** /api/ip-sync 建机去重（原 17-ctwo-server） */
    @Select("SELECT COUNT(*) FROM device " +
            "WHERE channelcode = #{channelcode} " +
            "  AND ip LIKE CONCAT(#{ipPrefix}, '%') " +
            "  AND addtime BETWEEN #{fromTs} AND #{toTs}")
    int countIpSyncDevices(@Param("ipPrefix") String ipPrefix,
                           @Param("fromTs") double fromTs,
                           @Param("toTs") double toTs,
                           @Param("channelcode") String channelcode);
    
    // 2. 全部删除（≤ maxResults 时使用）
    @Delete("DELETE FROM device " +
            "WHERE bind_phase = 0 " +
            "  AND channelcode = #{channelcode} " +
            "  AND id != #{id} " +
            "  AND ip LIKE CONCAT(#{ipPrefix}, '%') " +
            "  AND addtime BETWEEN #{fromTs} AND #{toTs}")
    int deleteAllSimilarDevices(@Param("ipPrefix") String ipPrefix,
                                @Param("fromTs") double fromTs,
                                @Param("toTs") double toTs,
                                @Param("channelcode") String channelcode,
                                @Param("id") Integer id);
    
    // 3. 保留1条，删除其余的（> maxResults 时使用）
    @Delete("DELETE FROM device " +
            "WHERE bind_phase = 0 " +
            "  AND channelcode = #{channelcode} " +
            "  AND id != #{id} " +
            "  AND ip LIKE CONCAT(#{ipPrefix}, '%') " +
            "  AND addtime BETWEEN #{fromTs} AND #{toTs} " +
            "  AND id NOT IN ( " +
            "    SELECT id FROM ( " +
            "      SELECT id " +
            "      FROM device " +
            "      WHERE bind_phase = 0 " +
            "        AND channelcode = #{channelcode} " +
            "        AND id != #{id} " +
            "        AND ip LIKE CONCAT(#{ipPrefix}, '%') " +
            "        AND addtime BETWEEN #{fromTs} AND #{toTs} " +
            "      ORDER BY addtime DESC " +
            "      LIMIT 1 " +
            "    ) AS keep " +
            "  )")
    int deleteSimilarDevicesKeepLatest(@Param("ipPrefix") String ipPrefix,
                                        @Param("fromTs") double fromTs,
                                        @Param("toTs") double toTs,
                                        @Param("channelcode") String channelcode,
                                        @Param("id") Integer id);
}
