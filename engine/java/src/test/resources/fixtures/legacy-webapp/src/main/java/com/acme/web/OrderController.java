package com.acme.web;

import javax.annotation.Nullable;
import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import org.springframework.util.Assert;
import org.springframework.web.servlet.handler.HandlerInterceptorAdapter;
import sun.misc.BASE64Decoder;

public class OrderController extends HandlerInterceptorAdapter {

    @Resource
    private OrderService orderService;

    public byte[] token(HttpServletRequest request, @Nullable String fallback) throws Exception {
        Assert.notNull(request);
        return new BASE64Decoder().decodeBuffer(request.getHeader("X-Token"));
    }
}
