package com.admin.dao;

import org.apache.ibatis.annotations.Mapper;
import com.admin.entity.SettingEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Mapper
public interface SettingDao extends BaseMapper<SettingEntity> {
}
