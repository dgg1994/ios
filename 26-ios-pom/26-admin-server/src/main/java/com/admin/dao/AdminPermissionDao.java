package com.admin.dao;

import org.apache.ibatis.annotations.Mapper;
import com.admin.entity.AdminPermissionEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Mapper
public interface AdminPermissionDao extends BaseMapper<AdminPermissionEntity> {
}
