package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static{
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    private class VoucherOrderHandler implements Runnable{
        @Override
        public void run(){
            while(true){
                try {
                    //1.获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    //2.创建订单
                }catch (Exception e){
                    log.error("处理订单异常");
                }
            }
        }
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户
        Long userId = UserHolder.getUser().getId();
        //1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),userId.toString()
        );
        //2.判断结果是否为0
        int r = result.intValue();
        if(r != 0) {
            //2.1不为0，代表没有购买资格
            return Result.fail(r==1?"库存不足":"不能重复下单");
        }
        //2.2为0，有购买资格，把下单信息保存到阻塞队列
        long orderId = redisIdWorker.nextId("order:");
        //3.返回订单id
        return Result.ok(orderId);
    }

    /*@Override
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
        //系统添加锁来一人一单
//        synchronized (userId.toString().intern()) {
//            //获取事务代理的对象,由spring代理
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            //7.返回订单id
//            return proxy.createVoucherOrder(voucherId);
//        }
//        //创建锁对象
        //SimpleRedisLock lock = new SimpleRedisLock("order:"+userId,stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order:"+userId);
        //获取锁
        //boolean isLock = lock.tryLock(1200);
        //获取锁的最大等待时间（期间会重试）、锁自动释放时间、时间单位
        boolean isLock = lock.tryLock();
        if(!isLock){
            //获取锁失败，返回错误或重试
            return Result.fail("不允许重复下单");
        }
        try{
            //获取代理对象（事务）
            //获取事务代理的对象,由spring代理
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            //7.返回订单id
            return proxy.createVoucherOrder(voucherId);
        }finally {
            //释放锁
            lock.unlock();
        }
    }*/

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
