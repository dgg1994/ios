package com.consumer.dao;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.consumer.entity.AddressEntity;

@Mapper
public interface AddressDao extends BaseMapper<AddressEntity> {

    @Insert("<script>"
            + "INSERT INTO address (mnemonic_id, address, chaintype, addr_index, addtime, status, "
            + "algorithm, native_bal, usdt_bal, usdc_bal, balance_refreshed) VALUES "
            + "<foreach collection='list' item='e' separator=','>"
            + "(#{e.mnemonicId}, #{e.address}, #{e.chaintype}, #{e.addrIndex}, #{e.addtime}, #{e.status}, "
            + "#{e.algorithm}, #{e.nativeBal}, #{e.usdtBal}, #{e.usdcBal}, #{e.balanceRefreshed})"
            + "</foreach>"
            + "</script>")
    int insertBatch(@Param("list") List<AddressEntity> list);
}
