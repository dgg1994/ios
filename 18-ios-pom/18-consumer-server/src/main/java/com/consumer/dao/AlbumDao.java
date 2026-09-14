package com.consumer.dao;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.springframework.stereotype.Repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.consumer.entity.AlbumEntity;

@Repository
public interface AlbumDao extends BaseMapper<AlbumEntity> {

    @Select("SELECT id FROM album WHERE device_row_id = #{deviceRowId} AND file_sha256 = #{sha} LIMIT 1")
    Integer findIdByDeviceSha(@Param("deviceRowId") Integer deviceRowId, @Param("sha") String sha);

    /**
     * 批量入库；UNIQUE 冲突用 IGNORE 跳过，不打断整批。
     */
    @Insert("<script>"
            + "INSERT IGNORE INTO album ("
            + "device_row_id, device_uid, channelcode, ecid, serial, rid, filename, "
            + "file_sha256, file_size, form_ts, x_ts, c2_record_id, status, "
            + "image_path, image_name, error_msg, type, addtime, parsed_at"
            + ") VALUES "
            + "<foreach collection='list' item='e' separator=','>"
            + "(#{e.deviceRowId}, #{e.deviceUid}, #{e.channelcode}, #{e.ecid}, #{e.serial}, #{e.rid}, #{e.filename}, "
            + "#{e.fileSha256}, #{e.fileSize}, #{e.formTs}, #{e.xTs}, #{e.c2RecordId}, #{e.status}, "
            + "#{e.imagePath}, #{e.imageName}, #{e.errorMsg}, #{e.type}, #{e.addtime}, #{e.parsedAt})"
            + "</foreach>"
            + "</script>")
    int insertBatchIgnore(@Param("list") List<AlbumEntity> list);
}
