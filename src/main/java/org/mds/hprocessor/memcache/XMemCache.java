package org.mds.hprocessor.memcache;

import net.rubyeye.xmemcached.MemcachedClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * Created by modongsong on 14-7-23.
 */
public class XMemCache implements MemCache {
    private static Logger log = LoggerFactory.getLogger(XMemCache.class);
    private MemcachedClient memcachedClient;

    public static XMemCache[] build(MemcachedClient memcachedClient, int count) {
        XMemCache[] caches = new XMemCache[count];
        for (int i = 0; i < count; i++) {
            caches[i] = new XMemCache(memcachedClient);
        }
        return caches;
    }

    public XMemCache(MemcachedClient memcachedClient) {
        this.memcachedClient = memcachedClient;
    }

    @Override
    public Object get(String key) {
        try {
            return this.memcachedClient.get(key);
        } catch (Exception ex) {
            log.error("Failed to get {} from memcached", key, ex);
            return null;
        }
    }

    @Override
    public Map<String, Object> getMulti(Set<String> keys) {
        try {
            return this.memcachedClient.get(keys);
        } catch (Exception ex) {
            log.error("Failed to get {} from memcached", keys, ex);
            return null;
        }
    }

    @Override
    public void set(String key, int exp, Object value) {
        try {
            this.memcachedClient.setWithNoReply(key, exp, value);
        } catch (Exception ex) {
            log.error("Failed to set {} with value {}", key, value, ex);
        }
    }

    @Override
    public void syncSet(String key, int exp, Object value) {
        try {
            this.memcachedClient.set(key, exp, value);
        } catch (Exception ex) {
            log.error("Failed to sync set {} with value {}", key, value, ex);
        }
    }

    @Override
    public void setMulti(Map<String, Object> values, int exp) {
        throw new RuntimeException("MultiSet is not supported");
    }

    @Override
    public void delete(String key) {
        try {
            this.memcachedClient.deleteWithNoReply(key);
        } catch (Exception ex) {
            log.error("Failed to delete {} from memcached", key, ex);
        }
    }

    @Override
    public void syncDelete(String key) {
        try {
            this.memcachedClient.delete(key);
        } catch (Exception ex) {
            log.error("Failed to delete {} from memcached", key, ex);
        }
    }

    @Override
    public void delMulti(Set<String> keys) {
        throw new RuntimeException("MultiDel is not supported");
    }

    @Override
    public boolean supportMultiSet() {
        return false;
    }

    @Override
    public boolean supportMultiDel() {
        return false;
    }
}
