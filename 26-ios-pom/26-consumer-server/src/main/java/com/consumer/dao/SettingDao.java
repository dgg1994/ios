package com.consumer.dao;

import org.apache.ibatis.annotations.Mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.consumer.entity.SettingEntity;

@Mapper
public interface SettingDao extends BaseMapper<SettingEntity> {
}
