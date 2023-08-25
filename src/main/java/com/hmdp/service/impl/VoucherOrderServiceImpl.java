package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;

    @Override
    public Result seckillVoucher(Long voucherId) {
        //1.查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //2.判断秒杀是否开始
        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
            //尚未开始
            return Result.fail("秒杀尚未开始！");
        }
        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
            //已经结束
            return Result.fail("秒杀已经结束！");
        }
        //4.判断库存是否充足
        if(voucher.getStock()<1){
            //库存不足
            return Result.fail("库存不足！");
        }
        //等事务提交后再释放锁，不然先释放锁再提交事务可能会带来并发问题
        Long userId = UserHolder.getUser().getId();
        synchronized (userId.toString().intern()) {
            //获取事务代理的对象,由spring代理
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            //7.返回订单id
            return proxy.createVoucherOrder(voucherId);
        }
    }
    //注意锁释放时机和事务是否生效
    @Transactional
    public Result createVoucherOrder(Long voucherId){
        //5.一人一单
        Long userId = UserHolder.getUser().getId();
        //5.1查询订单
        //悲观锁用于插入数据
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        //5.2判断是否存在
        if (count > 0) {
            //用户已经购买过
            return Result.fail("用户已经购买过一次！");
        }

        //6.扣减库存
        //乐观锁用于更新数据
        boolean success = seckillVoucherService.update()
                .setSql("stock=stock-1")  //set stock=stock-1
                .eq("voucher_id", voucherId).gt("stock", 0) //where id=? and stock=? ==> stock>0
                .update();
        if (!success) {
            //扣减失败
            return Result.fail("库存不足！");
        }

        //6.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //6.1订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //6.2用户id
        voucherOrder.setUserId(userId);
        //6.3代金券id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        //7.返回订单id
        return Result.ok(orderId);
    }
}
