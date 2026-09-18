package com.admin.dao;

import org.apache.ibatis.annotations.Mapper;
import com.admin.entity.DeviceEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Mapper
public interface DeviceDao extends BaseMapper<DeviceEntity> {
}
