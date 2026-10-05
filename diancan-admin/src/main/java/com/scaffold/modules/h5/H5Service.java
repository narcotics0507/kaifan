package com.scaffold.modules.h5;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.scaffold.common.exception.BusinessException;
import com.scaffold.common.result.ResultCode;
import com.scaffold.framework.redis.RedisUtils;
import com.scaffold.framework.satoken.CustomerAccessGuard;
import com.scaffold.framework.satoken.SessionUtils;
import com.scaffold.modules.system.entity.SysUser;
import com.scaffold.modules.system.mapper.SysUserRoleMapper;
import com.scaffold.modules.system.entity.SysUserRole;
import com.scaffold.modules.system.service.SysUserService;
import com.scaffold.modules.table.entity.DiningTable;
import com.scaffold.modules.table.service.DiningTableService;
import com.scaffold.modules.table.vo.DiningTableVO;
import com.scaffold.modules.order.entity.Order;
import com.scaffold.modules.order.service.OrderService;
import com.scaffold.modules.order.dto.OrderCreateDTO;
import com.scaffold.modules.order.dto.AddItemDTO;
import com.scaffold.modules.order.vo.OrderVO;
import com.scaffold.modules.cart.service.CartService;
import com.scaffold.modules.cart.vo.CartVO;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import org.springframework.http.ResponseCookie;

@Service @RequiredArgsConstructor
public class H5Service {
    private static final SecureRandom RANDOM=new SecureRandom();
    private final H5Properties config;
    private final SysUserService users;
    private final SysUserRoleMapper roles;
    private final DiningTableService tables;
    private final OrderService orders;
    private final CartService carts;
    private final RedisUtils redis;
    private final org.springframework.data.redis.core.StringRedisTemplate strings;
    private final CustomerAccessGuard guard;
    private final JdbcTemplate jdbc;

    public void enabled() {
        if(!config.isEnabled() || config.getSigningKey().length()<32) {
            throw new BusinessException(ResultCode.FORBIDDEN,"网页点餐尚未启用，请联系门店");
        }
    }
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> session(HttpServletRequest request,HttpServletResponse response) {
        enabled();
        String origin=request.getHeader("Origin");
        if(origin!=null && !origin.equals("http://"+request.getHeader("Host")) && !origin.equals("https://"+request.getHeader("Host"))) {
            throw new BusinessException(ResultCode.FORBIDDEN,"请从门店点餐网页进入");
        }
        String identity=null;
        if(request.getCookies()!=null) for(Cookie c:request.getCookies()) if("kaifan_guest".equals(c.getName())) identity=c.getValue();
        boolean known=identity!=null&&identity.matches("[a-f0-9]{64}");
        SysUser user=known?users.getOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername,"h5_"+DigestUtil.sha256Hex(identity).substring(0,40))):null;
        if(user==null) {
            String minute=String.valueOf(System.currentTimeMillis()/60000);
            Long count=strings.opsForValue().increment("h5:new:"+request.getRemoteAddr()+":"+minute);
            strings.expire("h5:new:"+request.getRemoteAddr()+":"+minute,Duration.ofSeconds(120));
            if(count>30) throw new BusinessException(ResultCode.PARAM_ERROR,"访问频繁，请稍后再试");
            byte[] bytes=new byte[32];RANDOM.nextBytes(bytes);identity=HexFormat.of().formatHex(bytes);
            user=new SysUser();user.setUsername("h5_"+DigestUtil.sha256Hex(identity).substring(0,40));
            user.setOpenid("h5:"+UUID.randomUUID());user.setPassword("!H5_VISITOR_NO_PASSWORD_LOGIN!");
            user.setNickname("点餐访客");user.setUserType("APP");user.setStatus(1);users.save(user);
        }
        if(!"APP".equals(user.getUserType()) || !Integer.valueOf(1).equals(user.getStatus())
            || !user.getOpenid().startsWith("h5:") || roles.selectCount(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId,user.getId()))>0) {
            throw new BusinessException(ResultCode.FORBIDDEN,"访客身份不可用，请联系门店");
        }
        StpUtil.login(user.getId());
        StpUtil.getSession().set("userType","APP").set("openid",user.getOpenid()).set("h5Guest",true);
        response.addHeader("Set-Cookie",ResponseCookie.from("kaifan_guest",identity).httpOnly(true)
            .secure(request.isSecure()||"https".equals(request.getHeader("X-Forwarded-Proto")))
            .sameSite("Lax").path("/api/app/h5").maxAge(Duration.ofDays(30)).build().toString());
        response.setHeader("Cache-Control","no-store");
        return Map.of("token",StpUtil.getTokenValue(),"nickname","点餐访客");
    }
    public void guest() {
        enabled();
        if(!Boolean.TRUE.equals(StpUtil.getSession().get("h5Guest"))) throw new BusinessException(ResultCode.FORBIDDEN,"请从网页点餐入口进入");
    }
    public String signature(DiningTable table) {
        enabled();
        try {
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(config.getSigningKey().getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((table.getId()+":"+table.getCode()).getBytes(StandardCharsets.UTF_8)));
        } catch(Exception e) {throw new IllegalStateException("H5 signing unavailable");}
    }
    public String link(DiningTable table) {
        String origin=config.getPublicOrigin().replaceAll("/+$","");
        if(!origin.matches("https?://[a-zA-Z0-9.:-]+")) throw new BusinessException(ResultCode.PARAM_ERROR,"网页点餐地址尚未配置");
        return origin+"/order/?table="+table.getCode()+"&key="+signature(table);
    }
    @Transactional(rollbackFor=Exception.class)
    public DiningTableVO join(String code,String key) {
        guest();
        DiningTable table=tables.getOne(new LambdaQueryWrapper<DiningTable>().eq(DiningTable::getCode,code).last("FOR UPDATE"));
        if(table==null || key==null || !MessageDigest.isEqual(signature(table).getBytes(StandardCharsets.UTF_8),key.getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException(ResultCode.FORBIDDEN,"桌台链接无效，请扫描桌面二维码");
        }
        return tables.bindBrowsingUser(table.getId(),SessionUtils.getCurrentOpenid());
    }
    public Map<String,Object> context() {
        guest();Object ref=redis.get("table:user-binding:"+SessionUtils.getCurrentOpenid());
        if(!(ref instanceof String binding)) return Map.of("active",false);
        String[] parts=binding.split("#",2);
        if(parts.length!=2 || !parts[0].matches("[0-9]+"))return Map.of("active",false);
        DiningTable table=tables.getById(Long.valueOf(parts[0]));
        if(table==null || !parts[1].equals(table.getCurrentSessionCode()) || !(Integer.valueOf(0).equals(table.getStatus()) || Integer.valueOf(1).equals(table.getStatus()))) return Map.of("active",false);
        return Map.of("active",true,"table",tables.getByCode(table.getCode()));
    }
    @Transactional(rollbackFor=Exception.class)
    public OrderVO submit(Long tableId,String sessionCode,String requestId,String remark) {
        return submit(tableId, sessionCode, requestId, remark, null);
    }
    @Transactional(rollbackFor=Exception.class)
    public OrderVO submit(Long tableId,String sessionCode,String requestId,String remark,Integer guestCount) {
        guest();String openid=SessionUtils.getCurrentOpenid();Long userId=StpUtil.getLoginIdAsLong();
        String fingerprint=DigestUtil.sha256Hex(tableId+":"+sessionCode+":"+Objects.toString(remark,"")+(guestCount==null?"":":"+guestCount));
        // The request row lock serializes retries, and commits with all order writes.
        jdbc.update("INSERT INTO h5_submission(user_id,request_id,fingerprint,table_id) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE request_id=request_id",userId,requestId,fingerprint,tableId);
        Map<String,Object> submitted=jdbc.queryForMap("SELECT fingerprint,order_id FROM h5_submission WHERE user_id=? AND request_id=? FOR UPDATE",userId,requestId);
        if(!fingerprint.equals(submitted.get("fingerprint")))throw new BusinessException(ResultCode.PARAM_ERROR,"重复请求内容不一致");
        if(submitted.get("order_id")!=null)return publicOrder(orders.getOrderDetail(((Number)submitted.get("order_id")).longValue()));
        // Serialize independent visitors at a table, including simultaneous first orders.
        DiningTable table=tables.getOne(new LambdaQueryWrapper<DiningTable>().eq(DiningTable::getId,tableId).last("FOR UPDATE"));
        guard.requireTable(tableId);
        if(table==null||!Objects.equals(sessionCode,table.getCurrentSessionCode()))throw new BusinessException(ResultCode.FORBIDDEN,"本次用餐已结束，请重新扫码");
        CartVO cart=carts.getCart(openid,tableId);
        if(cart.getItems()==null||cart.getItems().isEmpty())throw new BusinessException(ResultCode.CART_EMPTY);
        if(Boolean.TRUE.equals(cart.getHasUnavailableItems()))throw new BusinessException(ResultCode.PARAM_ERROR,"有菜品暂不可点，请调整购物车");
        List<Order> pending=orders.list(new LambdaQueryWrapper<Order>().eq(Order::getTableId,tableId).eq(Order::getTableSessionCode,sessionCode).eq(Order::getStatus,0).orderByAsc(Order::getCreateTime));
        OrderVO result;
        if(pending.isEmpty()) {
            OrderCreateDTO dto=new OrderCreateDTO();dto.setTableId(tableId);dto.setPaymentMode(1);dto.setOrderType(0);dto.setRemark(remark);dto.setGuestCount(guestCount);
            result=orders.createOrder(openid,dto);
        } else {
            Order bill = pending.get(0);
            if (guestCount != null && bill.getGuestCount() != null && bill.getGuestCount() > 0 && !guestCount.equals(bill.getGuestCount())) {
                throw new BusinessException(ResultCode.ORDER_STATUS_ERROR, "本桌人数已由其他顾客确认，请刷新账单后加菜；调整人数请联系前台");
            }
            result=null;
            for(var item:cart.getItems()) {
                AddItemDTO dto=new AddItemDTO();dto.setDishId(item.getDishId());dto.setQuantity(item.getQuantity());dto.setRemark(item.getRemark());
                result=orders.addItem(pending.get(0).getId(),dto);
            }
            Long resultId=result.getId();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit(){carts.clearCart(openid,tableId);}
            });
        }
        jdbc.update("UPDATE h5_submission SET order_id=? WHERE user_id=? AND request_id=?",result.getId(),userId,requestId);
        return publicOrder(result);
    }
    private OrderVO publicOrder(OrderVO vo) {vo.setCustomerOpenid(null);return vo;}
}
