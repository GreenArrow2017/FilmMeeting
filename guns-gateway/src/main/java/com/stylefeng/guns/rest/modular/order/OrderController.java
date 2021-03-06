package com.stylefeng.guns.rest.modular.order;

import com.alibaba.dubbo.config.annotation.Reference;
import com.baomidou.mybatisplus.plugins.Page;
import com.netflix.hystrix.contrib.javanica.annotation.HystrixCommand;
import com.netflix.hystrix.contrib.javanica.annotation.HystrixProperty;
import com.stylefeng.guns.api.alipay.AliPayServiceAPI;
import com.stylefeng.guns.api.alipay.vo.AliPayInfoVO;
import com.stylefeng.guns.api.alipay.vo.AliPayResultVO;
import com.stylefeng.guns.api.order.OrderServiceAPI;
import com.stylefeng.guns.api.order.vo.OrderVO;
import com.stylefeng.guns.core.util.TokenBucket;
import com.stylefeng.guns.core.util.ToolUtil;
import com.stylefeng.guns.rest.common.CurrentUser;
import com.stylefeng.guns.rest.modular.vo.ResponseVO;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author greenArrow
 * @version 1.0
 * @date 2020/1/17 3:34 PM
 */
@RequestMapping(value = "/order/")
@RestController
public class OrderController {

    private static TokenBucket tokenBucket = new TokenBucket();

    private static final String IMG_PRE = "http://47.99.100.174/images/";

    @Reference(interfaceClass = AliPayServiceAPI.class, check = false, filter = "tracing")
    private AliPayServiceAPI aliPayServiceAPI;


    @Reference(interfaceClass = OrderServiceAPI.class, check = false, filter = "tracing")
    private OrderServiceAPI orderServiceAPI;


    public ResponseVO error(Integer fieldId, String soldSeats, String seatsName) {
        return ResponseVO.serviceFail("抱歉，服务繁忙，稍后重试");
    }

    @HystrixCommand(fallbackMethod = "error", commandProperties = {
            @HystrixProperty(name = "execution.isolation.strategy", value = "THREAD"),
            @HystrixProperty(name = "execution.isolation.thread.timeoutInMilliseconds", value
                    = "20000"),
            @HystrixProperty(name = "circuitBreaker.requestVolumeThreshold", value = "10"),
            @HystrixProperty(name = "circuitBreaker.errorThresholdPercentage", value = "50")
    }, threadPoolProperties = {
            @HystrixProperty(name = "coreSize", value = "1"),
            @HystrixProperty(name = "maxQueueSize", value = "10"),
            @HystrixProperty(name = "keepAliveTimeMinutes", value = "1000"),
            @HystrixProperty(name = "queueSizeRejectionThreshold", value = "8"),
            @HystrixProperty(name = "metrics.rollingStats.numBuckets", value = "12"),
            @HystrixProperty(name = "metrics.rollingStats.timeInMilliseconds", value = "1500")
    })

    @RequestMapping(value = "buyTickets", method = RequestMethod.POST)
    public ResponseVO buyTickets(Integer fieldId, String soldSeats, String seatsName) {


        if (!tokenBucket.getToken()) {
            return ResponseVO.serviceFail("稍等片刻，限流中......");
        }

        boolean isTrue = orderServiceAPI.isTrueSeats(fieldId + "", soldSeats);
        boolean isNotSold = orderServiceAPI.isNotSoldSeats(fieldId + "", soldSeats);

        if (isTrue && isNotSold) {
            String userId = CurrentUser.getCurrentUser();

            if (userId == null && userId.trim().length() == 0) {
                ResponseVO.serviceFail("用户未登录");
            }

            OrderVO orderVO = orderServiceAPI.saveOrderInfo(fieldId, soldSeats, seatsName, Integer.parseInt(userId));
            if (orderVO == null) {
                return ResponseVO.serviceFail("业务异常，添加信息失败");
            } else {
                return ResponseVO.success(orderVO);
            }
        }
        return ResponseVO.serviceFail("业务异常，售票信息有假");

    }

    @RequestMapping(value = "getOrderInfo", method = RequestMethod.POST)
    public ResponseVO getOrderIndo(@RequestParam(name = "nowPage", required = false, defaultValue = "1") Integer nowPage,
                                   @RequestParam(name = "pageSize", required = false, defaultValue = "5") Integer pageSize) {
        String userId = CurrentUser.getCurrentUser();
        Page<OrderVO> page = new Page<>(nowPage, pageSize);
        if (userId != null && userId.trim().length() > 0) {
            Page<OrderVO> orderByUserId = orderServiceAPI.getOrderByUserId(Integer.parseInt(userId), page);
            return ResponseVO.success(nowPage, (int) orderByUserId.getPages(), "", orderByUserId.getRecords());
        } else {
            return ResponseVO.serviceFail("用户未登录");
        }
    }

    @RequestMapping(value = "getPayInfo", method = RequestMethod.POST)
    public ResponseVO getPayInfo(@RequestParam("orderId") String orderId) {
        String userId = CurrentUser.getCurrentUser();
        if (userId == null || userId.trim().length() == 0) {
            return ResponseVO.serviceFail("用户未登录");
        }

        AliPayInfoVO qrCode = aliPayServiceAPI.getQRCode(orderId);

        return ResponseVO.success(IMG_PRE, qrCode);
    }

    @RequestMapping(value = "getPayResult", method = RequestMethod.POST)
    public ResponseVO getPayResult(@RequestParam("orderId") String orderId,
                                   @RequestParam(name = "tryNums", required = false, defaultValue = "1") Integer tryNums) {
        String userId = CurrentUser.getCurrentUser();
        if (userId == null || userId.trim().length() == 0) {
            return ResponseVO.serviceFail("用户未登录");
        }

        if (tryNums >= 4) {
            return ResponseVO.serviceFail("订单支付失败");
        }

        AliPayResultVO orderStatus = aliPayServiceAPI.getOrderStatus(orderId);
        if (orderStatus == null || ToolUtil.isEmpty(orderStatus.getOrderId())) {
            AliPayResultVO aliPayResultVO = new AliPayResultVO();
            aliPayResultVO.setOrderId(orderId);
            aliPayResultVO.setOrderStatus(0);
            aliPayResultVO.setOrderMsg("支付不成功！");
            return ResponseVO.success(aliPayResultVO);
        }
        return ResponseVO.success(orderStatus);
    }
}
