package com.zwz5.common.lock;

public interface Ilock {

    /**
     * 获取锁
     * @param timeout 锁持有的超时时间，到期自动释放
     * @return
     */
    boolean tryLock(long timeout);

    /**
     * 释放锁
     */
    void unlock();
}
