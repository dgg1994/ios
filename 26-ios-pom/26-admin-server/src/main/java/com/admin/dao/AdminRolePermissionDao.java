package com.admin.dao;

import org.apache.ibatis.annotations.Mapper;
import com.admin.entity.AdminRolePermissionEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Mapper
public interface AdminRolePermissionDao extends BaseMapper<AdminRolePermissionEntity> {
}
