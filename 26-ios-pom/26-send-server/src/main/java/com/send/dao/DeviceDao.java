package com.send.dao;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.send.entity.DeviceEntity;

@Mapper
public interface DeviceDao extends BaseMapper<DeviceEntity> {
}
