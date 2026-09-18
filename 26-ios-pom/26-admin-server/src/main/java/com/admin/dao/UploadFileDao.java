package com.admin.dao;

import org.apache.ibatis.annotations.Mapper;
import com.admin.entity.UploadFileEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Mapper
public interface UploadFileDao extends BaseMapper<UploadFileEntity> {
}
