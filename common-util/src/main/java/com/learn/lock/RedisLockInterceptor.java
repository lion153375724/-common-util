package com.learn.lock;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import com.google.common.base.Joiner;
import com.learn.cache.redis.SpringRedisUtils;
import com.learn.common.spelkey.KeySpELAdviceSupport;
import org.apache.commons.lang3.StringUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.support.AopUtils;
import org.springframework.stereotype.Component;

/**
 * Created by IDEA
 * User: mashaohua
 * Date: 2016-09-28 18:08
 * Desc:
 */
@Aspect
@Component
public class RedisLockInterceptor extends KeySpELAdviceSupport {

    @Pointcut("@annotation(com.learn.lock.RedisLockable)")
    public void pointcut() {
    }

    @Around("pointcut()")
    public Object doAround(ProceedingJoinPoint point) throws Throwable {

        MethodSignature methodSignature = (MethodSignature) point.getSignature();
        Method targetMethod = AopUtils.getMostSpecificMethod(methodSignature.getMethod(), point.getTarget().getClass());
        String targetName = point.getTarget().getClass().getName();
        String methodName = point.getSignature().getName();
        Object target = point.getTarget();
        Object[] arguments = point.getArgs();

        RedisLockable redisLock = targetMethod.getAnnotation(RedisLockable.class);
        long expire = redisLock.expiration();
        String redisKey = getLockKey(redisLock, targetMethod, targetName, methodName, target, arguments);
        boolean isLock;
        if (redisLock.isWaiting()) {
            isLock = waitingLock(redisKey, expire, redisLock.retryCount(), redisLock.retryWaitTime());
        } else {
            isLock = noWaitingLock(redisKey, expire);
        }
        if(isLock) {
            long startTime = System.currentTimeMillis();
            try {
                return point.proceed();
            } finally {
                long parseTime = System.currentTimeMillis() - startTime;
                if (parseTime <= expire * 1000) {
                    unLock(redisKey);
                }
            }
        } else {
            throw new RedisLockException("您的操作太频繁，请稍后再试");
        }
    }

    private String getLockKey(RedisLockable redisLock, Method targetMethod, String targetName, String methodName,
                              Object target, Object[] arguments) {

        String[] keys = redisLock.key();
        String prefix = redisLock.prefix();
        StringBuilder sb = new StringBuilder("lock.");
        if (StringUtils.isEmpty(prefix)) {
            sb.append(targetName).append(".").append(methodName);
        } else {
            sb.append(prefix);
        }
        if (keys != null) {
            String keyStr = Joiner.on("+ '.' +").skipNulls().join(keys);
            SpELOperationContext context = getOperationContext(targetMethod, arguments, target, target.getClass());
            Object key = generateKey(keyStr, context);
            sb.append("#").append(key);
        }
        return sb.toString();
    }

    /**
     * 加锁
     *
     * @param key    redis key
     * @param expire 过期时间，单位秒
     * @return true:加锁成功，false，加锁失败
     */
    private boolean noWaitingLock(String key, long expire) {

        long value = System.currentTimeMillis() + expire * 1000;
        boolean status = SpringRedisUtils.setNX(key, value);

        if (status) {
            return true;
        }

        long oldExpireTime = SpringRedisUtils.get(key, Long.class);
        if (oldExpireTime < System.currentTimeMillis()) {
            //超时
            long newExpireTime = System.currentTimeMillis() + expire * 1000;
            Long currentExpireTime = SpringRedisUtils.getSet(key, newExpireTime, Long.class);
            if (currentExpireTime == null) {
                return true;
            }
            if (currentExpireTime.longValue() == oldExpireTime) {
                return true;
            }
        }
        return false;
    }

    /**
     * 等待锁
     *
     * @param key    redis key
     * @param expire 过期时间，单位秒
     * @return true:加锁成功，false，加锁失败
     */
    private boolean waitingLock(String key, long expire, int retryCount, int retryWaitTime) {
        int count = 0;
        while (retryCount == -1 || count <= retryCount) {
            if (noWaitingLock(key ,expire)) {
                return true;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(retryWaitTime);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            count++;
        }
        return false;
    }

    private void unLock(String key) {
        SpringRedisUtils.delete(key);
    }

}
