package com.admin.dao;

import org.apache.ibatis.annotations.Mapper;
import com.admin.entity.AdminUserEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Mapper
public interface AdminUserDao extends BaseMapper<AdminUserEntity> {
}
