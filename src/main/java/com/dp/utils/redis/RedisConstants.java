package com.dp.utils.redis;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login_code:";
    public static final String LOGIN_USER_KEY = "login_token:";
    public static final Long LOGIN_CODE_TTL = 15L;
    public static final Long LOGIN_USER_TTL = 30L;

    public static final String LOCK_SHOP_KEY = "lock_shop:";
    public static final String CACHE_SHOP_KEY = "cache_shop:";
    public static final Long LOCK_SHOP_TTL = 30L;
    public static final Long CACHE_SHOP_TTL = 30L;
    public static final Long CACHE_NULL_TTL = 2L;

    public static final String SHOP_TYPE_KEY = "shop_type";
    public static final String SHOP_TYPE_LOCK_KEY = "lock_shop_type";
    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String LOCK_ORDER_KEY = "lock:order:";

    public static final String BLOG_LIKED_KEY = "blog_liked:";
}

