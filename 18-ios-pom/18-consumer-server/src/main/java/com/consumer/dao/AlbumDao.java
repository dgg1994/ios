package com.consumer.dao;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.springframework.stereotype.Repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.consumer.entity.AlbumEntity;

@Repository
public interface AlbumDao extends BaseMapper<AlbumEntity> {

    @Select("SELECT id FROM album WHERE device_row_id = #{deviceRowId} AND file_sha256 = #{sha} LIMIT 1")
    Integer findIdByDeviceSha(@Param("deviceRowId") Integer deviceRowId, @Param("sha") String sha);
}
