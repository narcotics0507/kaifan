package com.scaffold.common.config;

import com.scaffold.common.exception.BusinessException;
import com.scaffold.common.result.ResultCode;
import com.scaffold.modules.system.vo.RouteVO;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.List;

/** 门店采用先吃后付、人工核对收款的精简功能配置，保留源码以便恢复。 */
@Data
@Component
@ConfigurationProperties(prefix = "restaurant.features")
public class RestaurantFeatures {
    private boolean coupons = false;
    private boolean membership = false;
    private boolean onlinePayment = false;
    private boolean autoClearTable = true;
    private boolean rush = false;
    private boolean specs = false;
    private boolean banners = false;
    private boolean reviews = false;
    private boolean feedback = false;
    private boolean dailySystemMenus = false;

    public void validatePromotions(Long couponId, Integer usePoints) {
        if (!coupons && couponId != null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "本店已停用优惠券");
        }
        if (!membership && usePoints != null && usePoints > 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "本店已停用会员积分");
        }
    }

    public void requireOnlinePayment() {
        if (!onlinePayment) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "本店采用线下收款，请到收银台结账");
        }
    }

    public boolean isDisabledApi(String path) {
        return (!coupons && (path.startsWith("/app/coupon") || path.startsWith("/admin/coupon")))
            || (!membership && (path.startsWith("/app/member") || path.startsWith("/admin/member")))
            || (!onlinePayment && (List.of("/app/payment/wechat", "/app/payment/alipay", "/app/payment/aa",
                 "/admin/payment/qrcode", "/admin/payment/split-bill").contains(path)
                 || path.startsWith("/wx/pay/")));
    }

    public void applyMenuVisibility(List<RouteVO> routes) {
        for (RouteVO route : routes) {
            if (route.getChildren() != null) applyMenuVisibility(route.getChildren());
            String path = route.getPath() == null ? "" : route.getPath();
            boolean hidden = (!specs && path.equals("/dish/spec"))
                || (!banners && (path.endsWith("/banner")))
                || (!coupons && path.endsWith("/coupon"))
                || (!membership && (path.startsWith("/marketing/member") || path.equals("/manage/member-user")))
                || (!reviews && path.startsWith("/device/review"))
                || (!feedback && path.equals("/device/feedback"))
                || (!dailySystemMenus && (path.equals("/manage") || path.startsWith("/manage/")
                    || path.equals("/system") || path.startsWith("/system/")
                    || path.equals("/log") || path.startsWith("/log/") || path.equals("/device/audit-log")))
                || (route.getChildren() != null && !route.getChildren().isEmpty()
                    && route.getChildren().stream().allMatch(child -> child.getMeta() != null && Boolean.TRUE.equals(child.getMeta().getHideInMenu())));
            if (hidden && route.getMeta() != null) route.getMeta().setHideInMenu(true);
        }
    }
}
