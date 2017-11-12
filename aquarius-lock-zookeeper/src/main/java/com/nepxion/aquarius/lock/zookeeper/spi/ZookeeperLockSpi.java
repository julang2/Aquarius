package com.nepxion.aquarius.lock.zookeeper.spi;

/**
 * <p>Title: Nepxion Aquarius</p>
 * <p>Description: Nepxion Aquarius</p>
 * <p>Copyright: Copyright (c) 2017</p>
 * <p>Company: Nepxion</p>
 * @author Haojun Ren
 * @email 1394997@qq.com
 * @version 1.0
 */

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.aopalliance.intercept.MethodInvocation;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.locks.InterProcessMutex;
import org.apache.curator.framework.recipes.locks.InterProcessReadWriteLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nepxion.aquarius.common.property.AquariusProperties;
import com.nepxion.aquarius.common.zookeeper.constant.ZookeeperConstant;
import com.nepxion.aquarius.common.zookeeper.handler.ZookeeperHandler;
import com.nepxion.aquarius.lock.entity.LockType;
import com.nepxion.aquarius.lock.exception.AopException;
import com.nepxion.aquarius.lock.spi.LockSpi;

public class ZookeeperLockSpi implements LockSpi {
    private static final Logger LOG = LoggerFactory.getLogger(ZookeeperLockSpi.class);

    private AquariusProperties properties;
    private CuratorFramework curator;

    // 可重入锁可重复使用
    private volatile Map<String, InterProcessMutex> lockMap = new ConcurrentHashMap<String, InterProcessMutex>();
    private volatile Map<String, InterProcessReadWriteLock> readWriteLockMap = new ConcurrentHashMap<String, InterProcessReadWriteLock>();
    private boolean lockCached = true;

    @Override
    public void initialize() {
        try {
            properties = ZookeeperHandler.createPropertyConfig(ZookeeperConstant.CONFIG_FILE);
            curator = ZookeeperHandler.createCurator(properties);
        } catch (Exception e) {
            LOG.error("Initialize Curator failed", e);
        }
    }

    @Override
    public void destroy() {
        ZookeeperHandler.closeCurator(curator);
    }

    @Override
    public Object invoke(MethodInvocation invocation, LockType lockType, String key, long leaseTime, long waitTime, boolean async, boolean fair) throws Throwable {
        if (curator == null) {
            throw new AopException("Curator isn't initialized");
        }

        if (!ZookeeperHandler.isStarted(curator)) {
            throw new AopException("Curator isn't started");
        }

        if (fair) {
            throw new AopException("Fair lock of Zookeeper isn't support for " + lockType);
        }

        if (async) {
            throw new AopException("Async lock of Zookeeper isn't support for " + lockType);
        }

        return invokeLock(invocation, lockType, key, leaseTime, waitTime);
    }

    private Object invokeLock(MethodInvocation invocation, LockType lockType, String key, long leaseTime, long waitTime) throws Throwable {
        InterProcessMutex interProcessMutex = null;
        try {
            interProcessMutex = getLock(lockType, key);
            boolean status = interProcessMutex.acquire(waitTime, TimeUnit.MILLISECONDS);
            if (status) {
                return invocation.proceed();
            }
        } finally {
            unlock(interProcessMutex);
        }

        return null;
    }

    // 锁节点路径，对应ZooKeeper一个永久节点，下挂一系列临时节点
    private String getPath(String key) {
        return properties.getString(ZookeeperConstant.ROOT_PATH) + "/" + key;
    }

    private InterProcessMutex getLock(LockType lockType, String key) {
        if (lockCached) {
            return getCachedLock(lockType, key);
        } else {
            return getNewLock(lockType, key);
        }
    }

    private InterProcessMutex getNewLock(LockType lockType, String key) {
        String path = getPath(key);
        switch (lockType) {
            case LOCK:
                return new InterProcessMutex(curator, path);
            case READ_LOCK:
                return getCachedReadWriteLock(lockType, key).readLock();
                // return new InterProcessReadWriteLock(curator, path).readLock();
            case WRITE_LOCK:
                return getCachedReadWriteLock(lockType, key).writeLock();
                // return new InterProcessReadWriteLock(curator, path).writeLock();
        }

        throw new AopException("Invalid Zookeeper lock type for " + lockType);
    }

    private InterProcessMutex getCachedLock(LockType lockType, String key) {
        String path = getPath(key);
        String newKey = path + "-" + lockType;

        InterProcessMutex lock = lockMap.get(newKey);
        if (lock == null) {
            InterProcessMutex newLock = getNewLock(lockType, key);
            lock = lockMap.putIfAbsent(newKey, newLock);
            if (lock == null) {
                lock = newLock;
            }
        }

        return lock;
    }

    private InterProcessReadWriteLock getCachedReadWriteLock(LockType lockType, String key) {
        String path = getPath(key);
        String newKey = path;

        InterProcessReadWriteLock readWriteLock = readWriteLockMap.get(newKey);
        if (readWriteLock == null) {
            InterProcessReadWriteLock newReadWriteLock = new InterProcessReadWriteLock(curator, path);
            readWriteLock = readWriteLockMap.putIfAbsent(newKey, newReadWriteLock);
            if (readWriteLock == null) {
                readWriteLock = newReadWriteLock;
            }
        }

        return readWriteLock;
    }

    private void unlock(InterProcessMutex interProcessMutex) throws Throwable {
        if (ZookeeperHandler.isStarted(curator)) {
            if (interProcessMutex.isAcquiredInThisProcess()) {
                interProcessMutex.release();
            }
        }
    }
}