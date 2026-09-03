package com.consume.dao;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.consume.entity.AlbumEntity;

public interface AlbumDao extends BaseMapper<AlbumEntity> {

    /** 同设备同文件哈希（对齐 Python album 去重） */
    @Select("SELECT id, device_row_id, status, c2_record_id, file_sha256, form_ts, image_path "
            + "FROM album WHERE device_row_id = #{deviceRowId} AND file_sha256 = #{fileSha256} "
            + "LIMIT 1")
    AlbumEntity findByDeviceAndSha(@Param("deviceRowId") Integer deviceRowId,
                                   @Param("fileSha256") String fileSha256);

    /** 同 c2 记录已写入过 album（重投去重） */
    @Select("SELECT id, device_row_id, status, c2_record_id, file_sha256, form_ts, image_path "
            + "FROM album WHERE c2_record_id = #{c2RecordId} LIMIT 1")
    AlbumEntity findByC2RecordId(@Param("c2RecordId") Integer c2RecordId);

    @Update("UPDATE album SET c2_record_id = #{c2RecordId} "
            + "WHERE id = #{id} AND (c2_record_id IS NULL OR c2_record_id = 0)")
    int fillC2RecordId(@Param("id") Integer id, @Param("c2RecordId") Integer c2RecordId);

    /** 本地已落盘、待上云（status=3） */
    @Select("SELECT id, device_row_id AS deviceRowId, image_path AS imagePath, image_name AS imageName, "
            + "status, form_ts AS formTs "
            + "FROM album WHERE status = 3 ORDER BY id ASC LIMIT #{limit}")
    java.util.List<AlbumEntity> listPendingCloudUpload(@Param("limit") int limit);

    /** 上云成功：仅当仍为 status=3 时回写，避免重复上传覆盖 */
    @Update("UPDATE album SET status = 1, image_path = #{imagePath}, error_msg = '', "
            + "parsed_at = #{parsedAt} WHERE id = #{id} AND status = 3")
    int markCloudOk(@Param("id") Integer id,
                    @Param("imagePath") String imagePath,
                    @Param("parsedAt") Double parsedAt);
}
