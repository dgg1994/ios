package com.ctwo.dao;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.springframework.stereotype.Repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ctwo.entity.AppListEntity;

@Repository
public interface AppListDao extends BaseMapper<AppListEntity>{

	@Select("select * from applist where device_row_id = #{deviceRowYd} and app_name = #{appName}")
	AppListEntity findOne(@Param("deviceRowYd") Integer deviceRowYd,@Param("appName") String appName);

}
