package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.alibaba.fastjson.JSONObject;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

import static com.hmdp.utils.RedisConstants.CACHE_SHOPTYPE_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryList() {
        String key = CACHE_SHOPTYPE_KEY;
        String shopTypeListJson = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(shopTypeListJson)){
            //3.存在，直接返回,Json字符串转集合对象返回
            List<ShopType> typeList = JSONObject.parseArray(shopTypeListJson, ShopType.class);
            return Result.ok(typeList);
        }
        //6.存在，写入Redis,value是shoptype转json
        List<ShopType> typeList = this.query().orderByAsc("sort").list();
        String typeListJson = JSONUtil.toJsonStr(typeList);
        stringRedisTemplate.opsForValue().set(key, typeListJson);
        return Result.ok(typeList);
    }

}
