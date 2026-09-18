package com.admin.dao;

import org.apache.ibatis.annotations.Mapper;
import com.admin.entity.AdminRoleEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Mapper
public interface AdminRoleDao extends BaseMapper<AdminRoleEntity> {
}
