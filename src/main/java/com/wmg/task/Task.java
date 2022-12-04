package com.wmg.task;

import cn.hutool.core.util.PhoneUtil;
import cn.hutool.core.util.StrUtil;
import cn.yiidii.pigeon.common.core.exception.BizException;
import com.alibaba.fastjson.JSONObject;
import com.wmg.Service.ExecService;
import com.wmg.Service.NewExecService;
import com.wmg.util.EmailUtil;
import com.wmg.util.TimeUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Description: 多线程执行定时任务 官网：www.fhadmin.org
 * Designer: jack
 * Date: 2017/8/10
 * Version: 1.0.0
 */
@Configuration
@Component//需要注册到bean中
@Slf4j
@SuppressWarnings("all")
//所有的定时任务都放在一个线程池中，定时任务启动时使用不同都线程。
public class Task implements SchedulingConfigurer {

    //启用多线程
    final ExecutorService  executor = Executors.newCachedThreadPool();
    //AtomicInteger用来计数
    AtomicInteger atomicInteger = new AtomicInteger(0);

    volatile int count = 0;

    //定时表达式
    @Value("${demo.corn}")
    private String corn;
    @Autowired
    private NewExecService newExecService;
    @Autowired
    private ExecService execService;
    //注入redis
    @Autowired
    private RedisTemplate redisTemplate;

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        //设定一个长度20的定时任务线程池
        //taskRegistrar.setScheduler(Executors.newScheduledThreadPool(30));
        taskRegistrar.addTriggerTask(() -> {
            try {
                process();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }, triggerContext ->{
            //此处的corn表达式可以换成数据库的方式接入，如使用Mapper
            if (corn.isEmpty()) {
                throw new BizException("定时打卡表达式为空");
            }
            return new CronTrigger(corn).nextExecutionTime(triggerContext);
        } );
    }

    /**
    *@Description: 执行打卡任务
    *@Param:
    *@return:
    *@Author: wmg
    *@date: 2022/6/9 1:06
    */
    public void process() throws InterruptedException {
        count = 0;
        Set<String> keys = redisTemplate.keys("XiaoMiYunDong_*");
        if (keys.size()>0) {
            for (String str : keys) {
                count++;
                //final int j=i; //关键是这一句代码，将 i 转化为  j，这样j 还是final类型的参与线程
                final String key = str;
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        String phoneNumber = "";
                        try{
                            Object redisData = redisTemplate.opsForValue().get(key);
                            //log.info("查询数据：{}",redisData);
                            if (!Objects.isNull(redisData)){
                                JSONObject responseJo = JSONObject.parseObject(redisData.toString());
//                                System.out.println(responseJo);
                                phoneNumber = responseJo.getString("phoneNumber");
                                String password = responseJo.getString("password");
                                Integer minSteps = responseJo.getInteger("minSteps");
                                Integer maxSteps = responseJo.getInteger("maxSteps");
                                Integer steps = ThreadLocalRandom.current().nextInt(minSteps, maxSteps+1);
                                //因不清楚手机打卡失败的原因，故调用两个不同方法
                                if (PhoneUtil.isMobile(phoneNumber)){
                                    execService.exec(phoneNumber,password,steps);
                                }else {
                                    newExecService.exec(phoneNumber,password,steps);
                                }

//                                System.out.println("当前线程："+atomicInteger.incrementAndGet()+"正在执行任务");
                            }
                        }catch(Exception e){
                            System.out.println(StrUtil.format("当前账号：{}打卡失败，打卡时间为：{},异常为：{}", phoneNumber,TimeUtil.getOkDate(new Date().toString()),e.getMessage()));
                        }
                    }
                });
                if (count == 30){
                    int number=(int)(Math.random()*(10)+600);
                    System.out.println("10分钟内打卡数达到30个，等待解黑休眠时间"+number+"秒后继续打卡");
                    Thread.sleep(number*1000);
                    count = 0;
//                    atomicInteger.set(0);
                }
            }

        }

    }


    /**
    *@Description: 校验失效账号
    *@Param:
    *@return:
    *@Author: wmg
    *@date: 2022/6/9 1:34
    */
    public void check() throws Exception {
        //将计数器归零
        count = 0;
        Set<String> keys = redisTemplate.keys("XiaoMiYunDong_*");
        if (keys.size() > 0) {
            for (String key : keys) {
                //执行三次打卡,定时10秒一次，三次失效，删除
                int flag = 0;
                String phoneNumber = "";
                String password = "";
                Integer minSteps = 1;
                Integer maxSteps = 100000;
                Object redisData = redisTemplate.opsForValue().get(key);
                for (int i = 0; i < 3; i++){
                    try{
                        if (!Objects.isNull(redisData)){
                            count++;
                            JSONObject responseJo = JSONObject.parseObject(redisData.toString());
                            phoneNumber = responseJo.getString("phoneNumber");
                            System.out.println(StrUtil.format("当前账号：{}，正在执行第{}次失效检测操作",phoneNumber,(i+1)));
                            password = responseJo.getString("password");
                            minSteps = responseJo.getInteger("minSteps");
                            maxSteps = responseJo.getInteger("maxSteps");
                            if (PhoneUtil.isMobile(phoneNumber)){
                                execService.check(phoneNumber, password);
                            }else {
                                newExecService.check(phoneNumber, password);
                            }

                        }else {
                            continue;
                        }
                    }catch(Exception e){
                        System.out.println("执行错误信息为："+e.getMessage());
                        flag++;
                    }

//                    int number=(int)(Math.random()*(2)+1);
//                    System.out.println(StrUtil.format("随机休眠{}秒，继续执行失效检测操作",number));
//                    Thread.sleep(number*1000);
                }

                if (flag == 3){
                    System.out.println(StrUtil.format("当前账号：{}，已失效，执行删除操作",phoneNumber));
                    //移除失效账号
                    redisTemplate.delete(key);
                    //新增失效账号
                    //表示多少秒过期，可以设置时间的计数单位，有分，小时，年，月，日等
                    //redisTemplate.opsForValue().set("XiaoMiYunDong_"+phoneNumber, jsonObject, 600, TimeUnit.SECONDS);
                    JSONObject jsonObject = new JSONObject();
                    jsonObject.put("phoneNumber",phoneNumber);
                    jsonObject.put("password",password);
                    jsonObject.put("minSteps",minSteps);
                    jsonObject.put("maxSteps",maxSteps);
                    redisTemplate.opsForValue().set("XMYD_ShiXiao_"+phoneNumber, jsonObject.toString());
                }
                if (count >= 30){
                    int number=(int)(Math.random()*(10)+600);
                    System.out.println("10分钟内打卡数达到30个，解黑休眠"+number+"秒，继续检测");
                    Thread.sleep(number*1000);
                    count = 0;
                }
                System.out.println();
                flag = 0;
            }
        }
    }


}