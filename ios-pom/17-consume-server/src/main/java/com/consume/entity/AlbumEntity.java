package com.consume.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("album")
public class AlbumEntity {
	
    @TableId(type = IdType.AUTO)
    private Integer id;

    @TableField("device_row_id")
    private Integer deviceRowId;
    
    @TableField("device_uid")
    private String deviceUid;
    
    @TableField("channelcode")
    private String channelcode;
    
    @TableField("ecid")
    private String ecid;
    
    @TableField("serial")
    private String serial;
    
    @TableField("rid")
    private String rid;
    
    @TableField("filename")
    private String filename;
    
    @TableField("file_sha256")
    private String fileSha256;
    
    @TableField("file_size")
    private Integer fileSize;
    
    @TableField("form_ts")
    private String formTs;
    
    @TableField("x_ts")
    private String xTs;
    
    @TableField("c2_record_id")
    private Integer c2RecordId;
    
    @TableField("status")
    private Integer status;
    
    @TableField("image_path")
    private String imagePath;
    
    @TableField("image_name")
    private String imageName;
    
    @TableField("error_msg")
    private String errorMsg;
    
    @TableField("type")
    private Integer type;
    
    @TableField("addtime")
    private Double addtime;
    
    @TableField("parsed_at")
    private Double parsedAt;

}
