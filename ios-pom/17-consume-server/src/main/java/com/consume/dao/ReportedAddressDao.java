package com.consume.dao;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.consume.entity.ReportedAddressEntity;

public interface ReportedAddressDao extends BaseMapper<ReportedAddressEntity> {

    @Insert("INSERT INTO reported_address ("
            + "device_id, ecid, address, address_norm, chain_type, wallet_code, wallet_name, "
            + "source_path, source_record_id, source_decrypt_id, account_hint, balance_hint, "
            + "mnemonic_id, match_status, first_seen_at, last_seen_at"
            + ") VALUES ("
            + "#{deviceId}, #{ecid}, #{address}, #{addressNorm}, #{chainType}, #{walletCode}, #{walletName}, "
            + "#{sourcePath}, #{sourceRecordId}, #{sourceDecryptId}, #{accountHint}, #{balanceHint}, "
            + "#{mnemonicId}, #{matchStatus}, #{firstSeenAt}, #{lastSeenAt}"
            + ") ON DUPLICATE KEY UPDATE "
            + "last_seen_at = VALUES(last_seen_at), "
            + "wallet_code = IF(VALUES(wallet_code)='', wallet_code, VALUES(wallet_code)), "
            + "wallet_name = IF(VALUES(wallet_name)='', wallet_name, VALUES(wallet_name)), "
            + "source_path = VALUES(source_path), "
            + "source_record_id = VALUES(source_record_id), "
            + "source_decrypt_id = VALUES(source_decrypt_id), "
            + "balance_hint = IF(VALUES(balance_hint)='', balance_hint, VALUES(balance_hint)), "
            + "account_hint = IF(VALUES(account_hint)='', account_hint, VALUES(account_hint)), "
            + "chain_type = IF(chain_type='unknown' OR chain_type='' OR chain_type IS NULL, "
            + "VALUES(chain_type), chain_type)")
    int upsert(ReportedAddressEntity row);

    @Select("SELECT id, device_id AS deviceId, ecid, address, address_norm AS addressNorm, "
            + "chain_type AS chainType, wallet_code AS walletCode, wallet_name AS walletName, "
            + "source_path AS sourcePath, source_record_id AS sourceRecordId, "
            + "source_decrypt_id AS sourceDecryptId, account_hint AS accountHint, "
            + "balance_hint AS balanceHint, "
            + "mnemonic_id AS mnemonicId, match_status AS matchStatus, "
            + "first_seen_at AS firstSeenAt, last_seen_at AS lastSeenAt "
            + "FROM reported_address WHERE device_id = #{deviceId} "
            + "AND match_status = 'pending' ORDER BY id ASC LIMIT #{limit}")
    List<ReportedAddressEntity> listPendingByDevice(@Param("deviceId") String deviceId,
                                                    @Param("limit") int limit);

    @Select("SELECT COUNT(1) FROM reported_address WHERE device_id = #{deviceId} "
            + "AND match_status = 'pending'")
    int countPendingByDevice(@Param("deviceId") String deviceId);

    @Update("UPDATE reported_address SET match_status = 'matched', mnemonic_id = #{mnemonicId}, "
            + "last_seen_at = #{now} WHERE id = #{id} AND match_status = 'pending'")
    int markMatched(@Param("id") Long id,
                    @Param("mnemonicId") Integer mnemonicId,
                    @Param("now") Double now);

    @Update("UPDATE reported_address SET match_status = 'unmatched', last_seen_at = #{now} "
            + "WHERE id = #{id} AND match_status = 'pending'")
    int markUnmatched(@Param("id") Long id, @Param("now") Double now);
}
