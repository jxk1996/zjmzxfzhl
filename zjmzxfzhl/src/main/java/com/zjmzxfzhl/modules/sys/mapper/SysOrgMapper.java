package com.zjmzxfzhl.modules.sys.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.zjmzxfzhl.common.aspect.annotation.DataPermission;
import com.zjmzxfzhl.common.permission.provider.OrgDataPermissionProvider;
import com.zjmzxfzhl.modules.sys.entity.SysOrg;

/**
 * 机构Mapper
 * 
 * @author 庄金明
 */
public interface SysOrgMapper extends BaseMapper<SysOrg> {
    /**
     * 查询机构列表
     * 
     * @param page
     * @param entity
     * @return
     */
    @DataPermission(providers = OrgDataPermissionProvider.class)
    public List<SysOrg> list(IPage<SysOrg> page, @Param("entity") SysOrg entity);
}
