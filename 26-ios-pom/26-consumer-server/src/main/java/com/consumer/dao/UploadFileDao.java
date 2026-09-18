package com.consumer.dao;

import org.apache.ibatis.annotations.Mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.consumer.entity.UploadFileEntity;

@Mapper
public interface UploadFileDao extends BaseMapper<UploadFileEntity> {
}
