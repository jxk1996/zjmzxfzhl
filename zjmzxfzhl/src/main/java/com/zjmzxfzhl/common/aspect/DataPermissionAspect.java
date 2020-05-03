package com.zjmzxfzhl.common.aspect;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.commons.beanutils.PropertyUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zjmzxfzhl.common.Constants;
import com.zjmzxfzhl.common.aspect.annotation.DataPermission;
import com.zjmzxfzhl.common.exception.SysException;
import com.zjmzxfzhl.common.permission.FilterGroup;
import com.zjmzxfzhl.common.permission.FilterOperate;
import com.zjmzxfzhl.common.permission.FilterRule;
import com.zjmzxfzhl.common.permission.SourceStrategy;
import com.zjmzxfzhl.common.permission.provider.AbstractDataPermissionProvider;
import com.zjmzxfzhl.common.util.ColumnUtils;
import com.zjmzxfzhl.common.util.CommonUtil;
import com.zjmzxfzhl.common.util.ShiroUtils;
import com.zjmzxfzhl.common.xss.SqlFilter;
import com.zjmzxfzhl.modules.sys.common.SessionObject;
import com.zjmzxfzhl.modules.sys.entity.SysDataPermission;
import com.zjmzxfzhl.modules.sys.entity.SysRole;
import com.zjmzxfzhl.modules.sys.entity.SysUser;
import com.zjmzxfzhl.modules.sys.service.SysDataPermissionService;

import lombok.extern.slf4j.Slf4j;

/**
 * 数据权限处理
 * 
 * @author 庄金明
 * @date 2020年3月23日
 */
@Aspect
@Component
@Slf4j
public class DataPermissionAspect {
    @Autowired
    private SysDataPermissionService sysDataPermissionService;

    @Pointcut("@annotation(com.zjmzxfzhl.common.aspect.annotation.DataPermission)||@annotation(com.zjmzxfzhl.common.aspect.annotation.DataPermission.DataPermissions)")
    public void pointCut() {
    }

    @SuppressWarnings("unchecked")
    @Around("pointCut()")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Class<?>[] paramTypes = method.getParameterTypes();
        DataPermission[] dataPermissions = method.getAnnotationsByType(DataPermission.class);
        SessionObject sessionObject = ShiroUtils.getSessionObject();
        if (sessionObject == null) {
            throw new SysException("登录超时，请重新登录");
        }
        SysUser sysUser = sessionObject.getSysUser();
        if (sysUser == null) {
            throw new SysException("登录超时，请重新登录");
        }
        SysRole role = sessionObject.getSysRole();
        if (role == null) {
            throw new SysException("登录超时，请重新登录");
        }
        List<SysRole> roles = sessionObject.getSysRoles();
        if (roles == null || roles.size() == 0) {
            throw new SysException("登录超时，请重新登录");
        }
        // admin用户用户所有数据权限
        if (Constants.ADMIN.equals(sysUser.getUserId())) {
            return joinPoint.proceed();
        }

        // 方法内的所有参数
        Object[] params = joinPoint.getArgs();
        if (params == null || params.length == 0) {
            throw new SysException("方法参数数量不能为空");
        }

        for (DataPermission dataPermission : dataPermissions) {
            String methodId = dataPermission.methodId();
            String[] tableNames = dataPermission.tableNames();
            String[] aliasNames = dataPermission.aliasNames();
            Class<AbstractDataPermissionProvider>[] providers = (Class<AbstractDataPermissionProvider>[]) dataPermission
                    .providers();
            String[] providerParams = dataPermission.providerParams();
            int index = dataPermission.index();
            if (params.length == 1) {
                index = 0;
            }
            if (index > params.length - 1) {
                throw new SysException("[index]错误");
            }
            Object param = params[index];
            if (param == null) {
                throw new SysException("第[" + index + "]参数为空");
            }

            String fieldName = dataPermission.fieldName();

            String userId = sysUser.getUserId();
            String roleId = role.getRoleId();

            /// 考虑到用户有多角色情况下说明开始
            /// 考虑到用户有多角色情况下，假设一个查询菜单功能F，A角色有菜单权限，但B角色没有菜单权限，
            /// 但B角色的数据权限配置的比较大，若合并2个角色的数据权限，可能会出现越权情况，所以不合并角色的数据权限，只查询当前登录用户所选角色的数据权限
            /// List<String> roleIdsList = new ArrayList<>();
            /// roles.forEach(r -> roleIdsList.add(r.getRoleId()));
            /// Object[] roleIdsArr = roleIdsList.toArray();
            /// 考虑到用户有多角色情况下说明结束

            List<FilterGroup> groups = new ArrayList<>();

            resolveProviders(sessionObject, methodId, providers, providerParams, userId, roleId, groups);
            resolveTableNames(methodId, tableNames, aliasNames, userId, roleId, groups);

            FilterGroup parentGroup = new FilterGroup();
            parentGroup.andGroups(groups);
            String authFilterSql = parentGroup.formatSql();
            log.info("数据权限sql:" + authFilterSql);

            if ("java.util.Map".equals(paramTypes[index].getName())) {
                Map<String, Object> map = (Map<String, Object>) param;
                map.put(fieldName, authFilterSql);
            } else {
                PropertyUtils.setSimpleProperty(param, fieldName, authFilterSql);
            }
        }

        return joinPoint.proceed();
    }

    private void resolveTableNames(String methodId, String[] tableNames, String[] aliasNames, String userId,
            String roleId, List<FilterGroup> groups) {
        if (tableNames.length > 0) {
            Map<String, String> aliasNamesMap = new HashMap<>(16);
            for (int i = 0; i < tableNames.length; i++) {
                aliasNamesMap.put(tableNames[i], aliasNames[i]);
            }
            QueryWrapper<SysDataPermission> queryWrapperByTableName = new QueryWrapper<>();
            queryWrapperByTableName.in("TABLE_NAME", (Object[]) tableNames).eq("SOURCE_STRATEGY",
                    SourceStrategy.TEXT.getKey());
            queryWrapperByTableName
                    .and(wrapper -> wrapper.nested(wrapper2 -> wrapper2.eq("ENTITY_TYPE", "1").eq("ENTITY_ID", roleId))
                            .or().nested(wrapper3 -> wrapper3.eq("ENTITY_TYPE", "2").eq("ENTITY_ID", userId)));
            if (methodId.length() == 0) {
                queryWrapperByTableName.and(wrapper -> wrapper.isNull("METHOD_ID").or().eq("METHOD_ID", ""));
            } else {
                queryWrapperByTableName.eq("METHOD_ID", methodId);
            }
            queryWrapperByTableName.orderByAsc("TABLE_NAME");
            List<SysDataPermission> sysDataPermissionsByTableName = this.sysDataPermissionService
                    .list(queryWrapperByTableName);
            Map<String, List<SysDataPermission>> tableNameColumnNameMap = new LinkedHashMap<>();
            for (SysDataPermission sysDataPermission : sysDataPermissionsByTableName) {
                String key = sysDataPermission.getTableName().toUpperCase() + "-"
                        + sysDataPermission.getColumnName().toUpperCase();
                List<SysDataPermission> tableNameColumnNameList = tableNameColumnNameMap.computeIfAbsent(key,
                        k -> new ArrayList<>());
                tableNameColumnNameList.add(sysDataPermission);
            }

            for (String key : tableNameColumnNameMap.keySet()) {
                List<SysDataPermission> tableNameColumnList = tableNameColumnNameMap.get(key);
                if (tableNameColumnList != null && tableNameColumnList.size() == 1) {
                    SysDataPermission sysDataPermission = tableNameColumnList.get(0);
                    FilterGroup group = new FilterGroup();
                    FilterOperate filterOperate = makeFilterOperate(sysDataPermission.getOperate());
                    String realValue = makeRealSqlValue(filterOperate, sysDataPermission.getClassName(),
                            sysDataPermission.getColumnName(), sysDataPermission.getValue());
                    FilterRule rule = new FilterRule(aliasNamesMap.get(sysDataPermission.getTableName()),
                            sysDataPermission.getColumnName(), filterOperate.getValue(), realValue);
                    group.andRule(rule);
                    groups.add(group);
                } else {
                    FilterGroup group = new FilterGroup();
                    for (SysDataPermission sysDataPermission : tableNameColumnList) {
                        FilterOperate filterOperate = makeFilterOperate(sysDataPermission.getOperate());
                        String realValue = makeRealSqlValue(filterOperate, sysDataPermission.getClassName(),
                                sysDataPermission.getColumnName(), sysDataPermission.getValue());
                        FilterRule rule = new FilterRule(aliasNamesMap.get(sysDataPermission.getTableName()),
                                sysDataPermission.getColumnName(), filterOperate.getValue(), realValue);
                        FilterGroup innerGroup = new FilterGroup(rule);
                        group.orGroup(innerGroup);
                    }
                    groups.add(group);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void resolveProviders(SessionObject sessionObject, String methodId,
            Class<AbstractDataPermissionProvider>[] providers, String[] providerParams, String userId, String roleId,
            List<FilterGroup> groups) throws ClassNotFoundException {
        // 【1】处理注解配置
        Map<Class<AbstractDataPermissionProvider>, String> providerMap = new HashMap<>(16);
        for (int i = 0; i < providers.length; i++) {
            Class<AbstractDataPermissionProvider> providerClass = providers[i];
            providerMap.put(providerClass, providerParams[i]);
        }

        // 【2】处理全局配置(methodId为NULL或空字符串)
        QueryWrapper<SysDataPermission> qwByProviderAndMethodIdIsNullOrEmpty = new QueryWrapper<>();
        // 查询全局配置(methodId为NULL或空字符串)的提供器配置，
        // 若注解有设定提供器且数据库也有全局配置，则以数据库配置覆盖之，
        // 若注解未配置则忽略数据库配置（即注解要有配置，全局配置才生效）
        qwByProviderAndMethodIdIsNullOrEmpty.eq("SOURCE_STRATEGY", SourceStrategy.SYSTEM.getKey());
        qwByProviderAndMethodIdIsNullOrEmpty
                .and(wrapper -> wrapper.nested(wrapper2 -> wrapper2.eq("ENTITY_TYPE", "1").eq("ENTITY_ID", roleId)).or()
                        .nested(wrapper3 -> wrapper3.eq("ENTITY_TYPE", "2").eq("ENTITY_ID", userId)));
        qwByProviderAndMethodIdIsNullOrEmpty.and(wrapper -> wrapper.isNull("METHOD_ID").or().eq("METHOD_ID", ""));
        qwByProviderAndMethodIdIsNullOrEmpty.orderByAsc("UPDATE_TIME");
        List<SysDataPermission> sysDataPermissionsByProviderAndMethodIdIsNullOrEmpty = this.sysDataPermissionService
                .list(qwByProviderAndMethodIdIsNullOrEmpty);
        for (SysDataPermission sysDataPermission : sysDataPermissionsByProviderAndMethodIdIsNullOrEmpty) {
            Class<AbstractDataPermissionProvider> providerClass = (Class<AbstractDataPermissionProvider>) Class
                    .forName(sysDataPermission.getSourceProvider());
            // 若注解有配置，注入数据库设置的参数值，以数据库参数为准
            if (providerMap.containsKey(providerClass)) {
                providerMap.put(providerClass, sysDataPermission.getSourceProviderParams());
            }
        }

        // 【2】处理methodId个性配置
        // 注解配置的methodId不为空，以methodId的配置为准
        if (methodId.length() > 0) {
            QueryWrapper<SysDataPermission> qwByProviderAndMethodIdIsNotNull = new QueryWrapper<>();
            qwByProviderAndMethodIdIsNotNull.eq("SOURCE_STRATEGY", SourceStrategy.SYSTEM.getKey());
            qwByProviderAndMethodIdIsNotNull
                    .and(wrapper -> wrapper.nested(wrapper2 -> wrapper2.eq("ENTITY_TYPE", "1").eq("ENTITY_ID", roleId))
                            .or().nested(wrapper3 -> wrapper3.eq("ENTITY_TYPE", "2").eq("ENTITY_ID", userId)));
            qwByProviderAndMethodIdIsNotNull.eq("METHOD_ID", methodId);
            qwByProviderAndMethodIdIsNullOrEmpty.orderByAsc("UPDATE_TIME");
            List<SysDataPermission> sysDataPermissionsByProviderAndMethodIdIsNotNull = this.sysDataPermissionService
                    .list(qwByProviderAndMethodIdIsNotNull);
            for (SysDataPermission sysDataPermission : sysDataPermissionsByProviderAndMethodIdIsNotNull) {
                Class<AbstractDataPermissionProvider> providerClass = (Class<AbstractDataPermissionProvider>) Class
                        .forName(sysDataPermission.getSourceProvider());
                // 直接注入数据库设置的参数值，以methodId配置的数据库参数为准
                providerMap.put(providerClass, sysDataPermission.getSourceProviderParams());
            }
        }

        for (Entry<Class<AbstractDataPermissionProvider>, String> entry : providerMap.entrySet()) {
            AbstractDataPermissionProvider provider = null;
            try {
                provider = entry.getKey().newInstance();
            } catch (Exception e) {
                throw new SysException("无法创建数据权限提供器:" + entry.getKey().getName());
            }
            // 注入参数值，若有特殊参数需要特殊处理，可以在实现类中重写该方法
            provider.setProviderParams(entry.getValue());
            FilterGroup group = provider.filter(sessionObject);
            if (group != null) {
                groups.add(group);
            }
        }
    }

    private String makeRealSqlValue(FilterOperate filterOperate, String className, String columnName, String value) {
        // 防止从数据库配置中注入非法sql
        SqlFilter.sqlInject(value);
        Class<?> clazz = null;
        Class<?> fieldClazz = null;
        try {
            clazz = Class.forName(className);
            String fieldName = ColumnUtils.camelName(columnName);
            fieldClazz = clazz.getDeclaredField(fieldName).getType();
        } catch (Exception e1) {
            return value;
        }

        String result = value;
        String type = fieldClazz.getName();
        switch (type) {
        case "java.lang.String":
            if (filterOperate == FilterOperate.IN || filterOperate == FilterOperate.BETWEEN) {
                result = CommonUtil.castStringBySeparator(value, ",");
            } else {
                result = "'" + value + "'";
            }
            break;
        case "java.util.Date":
            // TODO 日期格式，不同数据库处理方式不同
            if (filterOperate == FilterOperate.IN || filterOperate == FilterOperate.BETWEEN) {
                result = CommonUtil.castStringBySeparator(value, ",");
            } else {
                result = "'" + value + "'";
            }
            break;
        default:
            result = value;
            break;
        }
        return result;
    }

    private FilterOperate makeFilterOperate(String operate) {
        FilterOperate ruleOperate = FilterOperate.resolve(operate);
        if (ruleOperate == null) {
            ruleOperate = FilterOperate.EQ;
        }
        return ruleOperate;
    }

}
