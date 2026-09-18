package com.send.dao;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.send.entity.AdminUserEntity;

@Mapper
public interface AdminUserDao extends BaseMapper<AdminUserEntity> {
}
