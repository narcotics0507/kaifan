package com.scaffold.integration;

import com.scaffold.DiancanAdminApplication;
import com.scaffold.modules.system.service.WechatApiService;
import com.scaffold.modules.system.service.SysUserService;
import com.scaffold.modules.system.entity.SysUser;
import com.scaffold.modules.system.entity.SysUserRole;
import com.scaffold.modules.system.mapper.SysUserRoleMapper;
import com.scaffold.common.exception.BusinessException;
import com.scaffold.common.result.ResultCode;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import cn.hutool.crypto.digest.BCrypt;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** The WeChat boundary is mocked; local test DB and real Sa-Token/HTTP are used. */
@SpringBootTest(classes = DiancanAdminApplication.class)
@ActiveProfiles("test")
@AutoConfigureMockMvc
class WechatLoginIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired com.scaffold.modules.table.service.DiningTableService tables;
    @Autowired SysUserService users;
    @Autowired SysUserRoleMapper roles;
    @Autowired ObjectMapper json;
    @MockBean WechatApiService wechat;

    private String openid() { return "wx-it-" + UUID.randomUUID(); }
    private String login(String code) throws Exception {
        String body = mvc.perform(post("/app/auth/wechat-login").contentType("application/json")
                .content(json.writeValueAsString(java.util.Map.of("code", code))))
                .andExpect(jsonPath("$.code").value(200)).andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("session_key"));
        assertFalse(body.contains("session-secret"));
        return json.readTree(body).get("data").get("token").asText();
    }
    private SysUser find(String oid) {
        return users.getOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getOpenid, oid));
    }
    private SysUser seed(String oid, String type) {
        SysUser u = new SysUser(); u.setUsername("it-" + UUID.randomUUID());
        u.setPassword(BCrypt.hashpw("test-only-random-" + UUID.randomUUID()));
        u.setOpenid(oid); u.setUserType(type); u.setStatus(1); users.save(u); return u;
    }

    @Test void firstAndRepeatLoginKeepOneCustomerWithoutPhoneOrRoles() throws Exception {
        String oid = openid();
        when(wechat.code2Session(anyString())).thenReturn(new WechatApiService.WechatSessionInfo(oid,"session-secret"));
        String token = login("valid-first"); Long id = find(oid).getId();
        login("valid-next"); assertEquals(id, find(oid).getId());
        assertNull(find(oid).getPhone()); assertEquals("APP", find(oid).getUserType());
        assertEquals(0, roles.selectCount(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId,id)));
        verify(wechat, never()).getPhoneNumber(anyString());
        mvc.perform(get("/auth/info").header("Authorization",token))
                .andExpect(jsonPath("$.data.roles").isEmpty()).andExpect(jsonPath("$.data.permissions").isEmpty());
        for (String path : new String[]{"/admin/table/list", "/app/kitchen/tasks", "/system/config/list", "/route/getUserRoutes"}) {
            mvc.perform(get(path).header("Authorization",token)).andExpect(jsonPath("$.code").value(403));
        }
        com.scaffold.modules.table.entity.DiningTable table=new com.scaffold.modules.table.entity.DiningTable();
        table.setCode("WX-"+UUID.randomUUID());table.setName("微信登录测试桌");table.setCapacity(4);table.setStatus(0);tables.save(table);
        mvc.perform(put("/app/table/"+table.getId()+"/open")).andExpect(jsonPath("$.code").value(200));
        mvc.perform(put("/app/table/"+table.getId()+"/bind").header("Authorization",token)).andExpect(jsonPath("$.code").value(200));
        mvc.perform(get("/app/cart").param("tableId",table.getId().toString()).header("Authorization",token))
                .andExpect(jsonPath("$.code").value(200));
        mvc.perform(get("/app/cart").param("tableId","9999999").header("Authorization",token))
                .andExpect(jsonPath("$.code").value(403));
        mvc.perform(get("/app/order/table/9999999").header("Authorization",token))
                .andExpect(jsonPath("$.code").value(403));
        mvc.perform(put("/app/table/"+table.getId()+"/change").header("Authorization",token)
                .contentType("application/json").content("{\"targetTableId\":9999999}"))
                .andExpect(jsonPath("$.code").value(403));
    }
    @Test void rejectsInvalidAndMissingCodeWithoutCreatingAccounts() throws Exception {
        when(wechat.code2Session("expired")).thenThrow(new BusinessException(ResultCode.LOGIN_ERROR,"code已过期"));
        mvc.perform(post("/app/auth/wechat-login").contentType("application/json").content("{\"code\":\"expired\"}"))
                .andExpect(jsonPath("$.code").value(1001));
        mvc.perform(post("/app/auth/wechat-login").contentType("application/json").content("{\"openid\":\"spoof\"}"))
                .andExpect(jsonPath("$.code").value(400));
    }
    @Test void disabledCustomerCannotLogin() throws Exception {
        String oid=openid(); SysUser u=seed(oid,"APP"); u.setStatus(0); users.updateById(u);
        when(wechat.code2Session(anyString())).thenReturn(new WechatApiService.WechatSessionInfo(oid,"session-secret"));
        mvc.perform(post("/app/auth/wechat-login").contentType("application/json").content("{\"code\":\"valid\"}"))
                .andExpect(jsonPath("$.code").value(1002));
    }
    @Test void backendIdentityCannotBeUsedAsCustomer() throws Exception {
        String oid=openid(); seed(oid,"BACKEND");
        when(wechat.code2Session(anyString())).thenReturn(new WechatApiService.WechatSessionInfo(oid,"session-secret"));
        mvc.perform(post("/app/auth/wechat-login").contentType("application/json").content("{\"code\":\"valid\"}"))
                .andExpect(jsonPath("$.code").value(403));
    }
    @Test void customerWithBackendRoleCannotLogin() throws Exception {
        String oid=openid(); SysUser u=seed(oid,"APP"); SysUserRole r=new SysUserRole();r.setUserId(u.getId());r.setRoleId(1L);roles.insert(r);
        when(wechat.code2Session(anyString())).thenReturn(new WechatApiService.WechatSessionInfo(oid,"session-secret"));
        mvc.perform(post("/app/auth/wechat-login").contentType("application/json").content("{\"code\":\"valid\"}"))
                .andExpect(jsonPath("$.code").value(403));
    }
    @Test void phoneLoginIsClosedWithoutCallingWechat() throws Exception {
        mvc.perform(post("/app/auth/phone-login").contentType("application/json")
                .content("{\"code\":\"unused\",\"phoneCode\":\"unused\"}"))
                .andExpect(jsonPath("$.code").value(403));
        verifyNoInteractions(wechat);
    }
    @Test void simultaneousFirstLoginsCreateOnlyOneIdentity() throws Exception {
        String oid=openid();
        when(wechat.code2Session(anyString())).thenReturn(new WechatApiService.WechatSessionInfo(oid,"session-secret"));
        ExecutorService pool=Executors.newFixedThreadPool(2); CountDownLatch start=new CountDownLatch(1);
        try {
            Callable<String> work=()->{start.await();return login("concurrent-code");};
            Future<String> a=pool.submit(work);Future<String> b=pool.submit(work);start.countDown();
            assertNotNull(a.get(30,TimeUnit.SECONDS));assertNotNull(b.get(30,TimeUnit.SECONDS));
            assertEquals(1,users.count(new LambdaQueryWrapper<SysUser>().eq(SysUser::getOpenid,oid)));
        } finally {pool.shutdownNow();}
    }
}
