/**
 * Copyright 2010-2011 Nicholas Blair, Eric Dalquist
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.googlecode.ehcache.annotations.resolver;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import net.sf.ehcache.CacheManager;
import net.sf.ehcache.Ehcache;
import net.sf.ehcache.ObjectExistsException;
import net.sf.ehcache.constructs.blocking.CacheEntryFactory;
import net.sf.ehcache.constructs.blocking.SelfPopulatingCache;
import net.sf.ehcache.util.ProductInfo;

import org.aopalliance.intercept.MethodInvocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.SchedulingTaskExecutor;
import org.springframework.util.StringUtils;

import com.googlecode.ehcache.annotations.CacheNotFoundException;
import com.googlecode.ehcache.annotations.Cacheable;
import com.googlecode.ehcache.annotations.DecoratedCacheType;
import com.googlecode.ehcache.annotations.RefreshingCacheEntryFactory;
import com.googlecode.ehcache.annotations.RefreshingSelfPopulatingCache;
import com.googlecode.ehcache.annotations.SelfPopulatingCacheScope;
import com.googlecode.ehcache.annotations.TriggersRemove;
import com.googlecode.ehcache.annotations.support.TaskSchedulerAdapter;

/**
 * Cache resolver that simply looks up the specified caches by name and returns {@link SingletonCacheableCacheResolver} or
 * {@link SingletonTriggersRemoveCacheResolver} instances.
 * 
 * @author Eric Dalquist
 * @version $Revision$
 */
public class DefaultCacheResolverFactory implements CacheResolverFactory {
    /**
     * Logger available to subclasses.
     */
    protected final Logger logger = LoggerFactory.getLogger(this.getClass());
    
    private final boolean badSelfPopulatingCache;
    private final ConcurrentMap<String, SelfPopulatingCacheTracker> selfPopulatingCaches = new ConcurrentHashMap<String, SelfPopulatingCacheTracker>(); 
    private final CacheManager cacheManager;
    private boolean createCaches = false;
    private SelfPopulatingCacheScope selfPopulatingCacheScope = SelfPopulatingCacheScope.SHARED;
    private TaskSchedulerAdapter scheduler;
    private SchedulingTaskExecutor executor;
    
    public DefaultCacheResolverFactory(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
        
        final ProductInfo productInfo = new ProductInfo();
        final String version = productInfo.getVersion();
        this.badSelfPopulatingCache = version.equals("2.3.0") || version.equals("2.3.1");
    }

    public void setScheduler(TaskSchedulerAdapter scheduler) {
        this.scheduler = scheduler;
    }
    
    public void setExecutor(SchedulingTaskExecutor executor) {
        this.executor = executor;
    }

    public boolean isCreateCaches() {
        return this.createCaches;
    }

    public void setCreateCaches(boolean createCaches) {
        this.createCaches = createCaches;
    }
    
    public void setSelfPopulatingCacheScope(SelfPopulatingCacheScope selfPopulatingCacheScope) {
        this.selfPopulatingCacheScope = selfPopulatingCacheScope;
    }

    protected CacheManager getCacheManager() {
        return this.cacheManager;
    }

    /* (non-Javadoc)
     * @see com.googlecode.ehcache.annotations.resolver.CacheResolverFactory#getCacheResolver(com.googlecode.ehcache.annotations.Cacheable, java.lang.reflect.Method)
     */
    public CacheableCacheResolver getCacheResolver(Cacheable cacheable, Method method) {
        final String cacheName = cacheable.cacheName();
        Ehcache cache = this.getCache(cacheName);
        
        ThreadLocal<MethodInvocation> entryFactory = null; 
        
        final DecoratedCacheType decoratedCacheType = DecoratedCacheType.getDecoratedCacheType(cacheable, method);
        if (decoratedCacheType == DecoratedCacheType.SELF_POPULATING_CACHE || decoratedCacheType == DecoratedCacheType.REFRESHING_SELF_POPULATING_CACHE) {
            if (this.badSelfPopulatingCache) {
                logger.error("SelfPopulatingCache in Ehcache 2.3.0 & 2.3.1 has a bug which can result in unexpected behavior, see EHC-828. {} may not behave as expected", cacheName);
            }
            
            final int selfPopulatingTimeout = cacheable.selfPopulatingTimeout();
            final long refreshInterval = cacheable.refreshInterval();
            final SelfPopulatingCacheTracker selfPopulatingCacheTracker = this.createSelfPopulatingCacheInternal(cache, selfPopulatingTimeout, decoratedCacheType, refreshInterval);
            
            cache = selfPopulatingCacheTracker.selfPopulatingCache;
            entryFactory = selfPopulatingCacheTracker.cacheEntryFactory;
        }
        
        final String exceptionCacheName = cacheable.exceptionCacheName();
        if (StringUtils.hasLength(exceptionCacheName)) {
            final Ehcache exceptionCache = this.getCache(exceptionCacheName);
            return new SingletonCacheableCacheResolver(cache, entryFactory, exceptionCache);
        }

        return new SingletonCacheableCacheResolver(cache, entryFactory);
    }

    /* (non-Javadoc)
     * @see com.googlecode.ehcache.annotations.resolver.CacheResolverFactory#getCacheResolver(com.googlecode.ehcache.annotations.TriggersRemove, java.lang.reflect.Method)
     */
    public TriggersRemoveCacheResolver getCacheResolver(TriggersRemove triggersRemove, Method method) {
        final String[] cacheNames = triggersRemove.cacheName();
        final Set<Ehcache> caches = new LinkedHashSet<Ehcache>(cacheNames.length);
        for (final String cacheName : cacheNames) {
            final Ehcache cache = this.getCache(cacheName);
            caches.add(cache);
        }
        
        return new SingletonTriggersRemoveCacheResolver(caches);
    }

    protected Ehcache getCache(String cacheName) {
        final CacheManager cacheManager = this.getCacheManager();
        
        Ehcache cache = cacheManager.getEhcache(cacheName);
        if (cache == null) {
            if (this.createCaches) {
                this.logger.warn("No cache named '{}' exists, it will be created from the defaultCache", cacheName);
                try {
                    cacheManager.addCache(cacheName);
                }
                catch (ObjectExistsException oee) {
                    this.logger.trace("Race condition creating missing cache '{}', ignoring and retrieving existing cache", cacheName);
                }
                cache = cacheManager.getEhcache(cacheName);
            }
            else {
                throw new CacheNotFoundException(cacheName);
            }
        }
        return cache;
    }
    
    /**
     * Creates or retrieves a SelfPopulatingCacheTracker for the specified cache depending on the
     * configured {@link SelfPopulatingCacheScope}
     * 
     * @param cache The cache to create a self populating instance of
     * @return The SelfPopulatingCache and corresponding factory object to use
     */
    protected final SelfPopulatingCacheTracker createSelfPopulatingCacheInternal(Ehcache cache, int timeout, DecoratedCacheType type, long refreshinterval) {
        //If method scoped just create a new instance 
        if (SelfPopulatingCacheScope.METHOD == this.selfPopulatingCacheScope) {
            return this.createSelfPopulatingCache(cache, timeout, type, refreshinterval);
        }

        //Shared scope, try loading the instance from local Map
        boolean newCache = false;
        
        //See if there is a cached SelfPopulatingCache for the name
        final String cacheName = cache.getName();
        SelfPopulatingCacheTracker selfPopulatingCacheTracker = this.selfPopulatingCaches.get(cacheName);
        if (selfPopulatingCacheTracker == null) {
            selfPopulatingCacheTracker = this.createSelfPopulatingCache(cache, timeout, type, refreshinterval);
            
            //do putIfAbsent to handle concurrent creation. If a value is returned it was already put and that
            //value should be used. If no value was returned the newly created selfPopulatingCache should be used
            final SelfPopulatingCacheTracker existing = this.selfPopulatingCaches.putIfAbsent(cacheName, selfPopulatingCacheTracker);
            if (existing != null) {
                selfPopulatingCacheTracker = existing;
            }
            //If the new cache was created and didn't replace an existing entry in the map
            else {
                newCache = true;
            }
        }

        if (!newCache) {
            //Check if the timeouts match
            final int timeoutMillis = selfPopulatingCacheTracker.selfPopulatingCache.getTimeoutMillis();
            if (timeoutMillis != timeout) {
                this.logger.warn("SelfPopulatingCache " + cacheName + " was already created by another annotation but has a different timeout of " + timeoutMillis + ". The timeout " + timeout + " for the current annotation will be ignored.", new Throwable());
            }
        }
        
        return selfPopulatingCacheTracker;
    }

    /**
     * Create a new {@link SelfPopulatingCache} and corresponding {@link CacheEntryFactory}
     */
    protected SelfPopulatingCacheTracker createSelfPopulatingCache(Ehcache cache, int timeout, DecoratedCacheType type, long refreshinterval) {
        final SelfPopulatingCache selfPopulatingCache;
        final ThreadLocal<MethodInvocation> invocationLocal;
        
        switch (type) {
            case REFRESHING_SELF_POPULATING_CACHE: {
                final RefreshingCacheEntryFactory cacheEntryFactory = new RefreshingCacheEntryFactory();
                selfPopulatingCache = new RefreshingSelfPopulatingCache(cache, cacheEntryFactory, scheduler, executor, refreshinterval);
                invocationLocal = cacheEntryFactory.entryFactory;
                break;
            }
            case SELF_POPULATING_CACHE: {
                final ThreadLocalCacheEntryFactory cacheEntryFactory = new ThreadLocalCacheEntryFactory();
                selfPopulatingCache = new SelfPopulatingCache(cache, cacheEntryFactory);
                invocationLocal = cacheEntryFactory.entryFactory;
                break;
            }
            default: {
                throw new IllegalArgumentException("DecoratedCacheType " + type + " is not a supported self-populating type");
            }
        }
        
        selfPopulatingCache.setTimeoutMillis(timeout);
        return new SelfPopulatingCacheTracker(selfPopulatingCache, invocationLocal);
    }
    
    protected static class SelfPopulatingCacheTracker {
        public final SelfPopulatingCache selfPopulatingCache;
        public final ThreadLocal<MethodInvocation> cacheEntryFactory;
        
        public SelfPopulatingCacheTracker(SelfPopulatingCache selfPopulatingCache, ThreadLocal<MethodInvocation> cacheEntryFactory) {
            this.selfPopulatingCache = selfPopulatingCache;
            this.cacheEntryFactory = cacheEntryFactory;
        }
    }
}
