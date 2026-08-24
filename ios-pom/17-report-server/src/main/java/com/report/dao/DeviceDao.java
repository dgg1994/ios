package com.report.dao;

import java.util.List;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.springframework.stereotype.Repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.report.entity.DeviceEntity;

@Repository
public interface DeviceDao extends BaseMapper<DeviceEntity>{

	@Select("select * from device where deviceid = #{deviceid}")
	DeviceEntity findDeviceId(@Param("deviceid") String deviceid);

	@Select("SELECT id, deviceid, channelcode, domain, ip, addtime, ipstatus, devicestatus, " +
	        "onlinestatus, ecid, udid, serial, model, bind_phase, last_event_at, " +
	        "device_name, ios_version, hardware_model, carrier_names, c2_series " +
	        "FROM device " +
	        "WHERE bind_phase = 0 " +
	        "  AND channelcode = #{channelcode} " +
	        "  AND id != #{id} " +
	        "  AND ip LIKE CONCAT(#{ipPrefix}, '%') " +
//	        "  AND REPLACE(ios_version, 'IOS ', '') LIKE CONCAT(#{versionPrefix}, '%') " +
	        "  AND addtime BETWEEN #{fromTs} AND #{toTs} " +
	        "ORDER BY addtime DESC " +
	        "LIMIT #{maxResults}")
	List<DeviceEntity> findSimilarDevicesTwo(@Param("ipPrefix") String ipPrefix,
	        @Param("fromTs") double fromTs,
	        @Param("toTs") double toTs,
	        @Param("maxResults") int maxResults,
	        @Param("channelcode") String channelcode,
	        @Param("id") Integer id);

	@Delete("delete from device "+
			 "WHERE bind_phase = 0 " +
		        "  AND channelcode = #{channelcode} " +
		        "  AND id != #{id} " +
		        "  AND ip LIKE CONCAT(#{ipPrefix}, '%') " +
//		        "  AND REPLACE(ios_version, 'IOS ', '') LIKE CONCAT(#{versionPrefix}, '%') " +
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
