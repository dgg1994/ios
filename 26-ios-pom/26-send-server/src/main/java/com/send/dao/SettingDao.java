package com.send.dao;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.send.entity.SettingEntity;

@Mapper
public interface SettingDao extends BaseMapper<SettingEntity> {
}
