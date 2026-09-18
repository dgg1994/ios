package com.admin.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("upload_files")
public class UploadFileEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("uploadId")
    private String uploadId;
    @TableField("deviceId")
    private String deviceId;
    @TableField("fileName")
    private String fileName;
    @TableField("fileSize")
    private Long fileSize;
    @TableField("chunkSize")
    private Long chunkSize;
    @TableField("expectedChunks")
    private Integer expectedChunks;
    private String status;
    @TableField("storagePath")
    private String storagePath;
    @TableField("diskPath")
    private String diskPath;
    @TableField("sessionPath")
    private String sessionPath;
    @TableField("created_at")
    private Date createdAt;
    @TableField("updated_at")
    private Date updatedAt;
    @TableField("completed_at")
    private Date completedAt;
    private String sha256;
}
