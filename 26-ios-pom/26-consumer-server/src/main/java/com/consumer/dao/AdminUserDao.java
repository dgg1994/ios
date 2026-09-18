package com.consumer.dao;

import org.apache.ibatis.annotations.Mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.consumer.entity.AdminUserEntity;

@Mapper
public interface AdminUserDao extends BaseMapper<AdminUserEntity> {
}
