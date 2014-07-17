package org.mds.hprocessor.memcache;

import com.google.common.base.Preconditions;
import org.mds.hprocessor.processor.Processor;
import org.mds.hprocessor.processor.ProcessorBatchHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Created by Randall.mo on 14-6-9.
 */
public class MemcacheGetProcessor extends MemcacheProcessor {
    private static final Logger log = LoggerFactory.getLogger(MemcacheGetProcessor.class);
    protected final static int DEFAULT_BATCH_SIZE = 256;
    protected final static int MIN_BATCH_SIZE = 16;
    protected Processor<GetObject> getProcessor;
    protected MemcacheGetter getter;
    protected MemcacheCache memcacheCache;

    protected class GetObject {
        public GetObject(String key, int retryCount, GetCallback callback) {
            this.key = key;
            this.callback = callback;
            this.retryCount = retryCount;
        }

        String key;
        int retryCount, retryTimes;
        long submitTime = System.currentTimeMillis();
        GetCallback callback;

        public boolean allowRetry() {
            return retryTimes++ < retryCount;
        }

        public boolean isTimeout() {
            return getTimeUnit.convert(System.currentTimeMillis() - submitTime,
                    TimeUnit.MILLISECONDS) > getTimeout;
        }
    }

    private MemcacheGetProcessor() {
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public static class Builder extends ProcessorBuilder<GetObject, Builder, MemcacheGetProcessor> {
        int batchSize = DEFAULT_BATCH_SIZE;
        MemcacheGetter[] getters;
        MemcacheCache memcacheCache;

        protected Builder() {
        }

        public Builder setCache(MemcacheCache cache) {
            this.memcacheCache = cache;
            return this;
        }

        public Builder setBatchSize(int batchSize) {
            if (batchSize > MIN_BATCH_SIZE)
                this.batchSize = batchSize;
            return this;
        }

        public Builder setGetters(MemcacheGetter[] getters) {
            return this.setGetters(0, getters);
        }

        public Builder setGetters(int workerCount, MemcacheGetter[] getters) {
            Preconditions.checkArgument(getters != null && getters.length > 0, "Getters can not be empty.");
            if (getters.length > workerCount) {
                this.getters = getters;
            } else {
                this.getters = new MemcacheGetter[workerCount];
                Random random = new Random();
                for (int i = 0; i < getters.length; i++) {
                    this.getters[i] = getters[i];
                }
                for (int i = getters.length; i < workerCount; i++) {
                    this.getters[i] = getters[random.nextInt(getters.length)];
                }
            }
            this.getters = getters;
            return this;
        }

        public MemcacheGetProcessor build() {
            Preconditions.checkArgument(this.getters != null && this.getters.length > 0, "Getters must be set.");
            MemcacheGetProcessor memcacheGetProcessor = new MemcacheGetProcessor();
            ProcessorBatchHandler<GetObject>[] handlers = new ProcessorBatchHandler[getters.length];
            for (int i = 0; i < getters.length; i++) {
                handlers[i] = new GetProcessorHandler(getters[i], memcacheGetProcessor);
            }
            memcacheGetProcessor.getter = getters[0];
            this.setTimeout(memcacheGetProcessor);
            if (this.memcacheCache != null) {
                this.memcacheCache.setMemcacheGetter(memcacheGetProcessor.getter);
                memcacheGetProcessor.memcacheCache = this.memcacheCache;
            }
            Processor.BatchBuilder batchBuilder = this.builder();
            if (batchBuilder != null) {
                memcacheGetProcessor.getProcessor = batchBuilder.addNext(this.batchSize, handlers).build();
            }

            return memcacheGetProcessor;
        }
    }

    private static class GetProcessorHandler implements ProcessorBatchHandler<GetObject> {
        private MemcacheGetter memcacheGetter;
        private MemcacheGetProcessor memcacheGetProcessor;

        public GetProcessorHandler(MemcacheGetter memcacheGetter, MemcacheGetProcessor memcacheGetProcessor) {
            this.memcacheGetter = memcacheGetter;
            this.memcacheGetProcessor = memcacheGetProcessor;
        }

        @Override
        public void process(List<GetObject> objects) {
            List<String> keys = new ArrayList();
            for (GetObject getObject : objects) {
                keys.add(getObject.key);
            }
            Map<String, Object> result = null;
            try {
                result = this.memcacheGetter.getBulk(keys);
            } finally {
                for (GetObject getObject : objects) {
                    Object value = null;
                    if (result != null)
                        value = result.get(getObject.key);
                    if (value == null) {
                        if (getObject.isTimeout()) {
                            if (getObject.callback != null) {
                                getObject.callback.timeout(getObject.key);
                            }
                        } else if (getObject.allowRetry()) {
                            this.memcacheGetProcessor.submit(getObject);
                        } else if (getObject.callback != null) {
                            getObject.callback.handleNull(getObject.key);
                        }
                    } else if (getObject.callback != null) {
                        if (this.memcacheGetProcessor.memcacheCache != null) {
                            this.memcacheGetProcessor.memcacheCache.cache(getObject.key, value);
                        }
                        getObject.callback.handle(getObject.key, value);
                    }
                }
            }
        }
    }

    private void submit(GetObject getObject) {
        this.getProcessor.submit(getObject);
    }

    public Object get(String key) {
        Object value = this.getter.get(key);
        if (this.memcacheCache != null) {
            this.memcacheCache.cache(key, value);
        }
        return value;
    }

    public void get(String key, GetCallback callback) {
        if (!this.getProcessor.trySubmit(new GetObject(key, 0, callback),
                this.submitTimeout, this.submitTimeUnit)) {
            if (callback != null) {
                callback.timeout(key);
            }
        }
    }

    public void get(String key, int retryCount, GetCallback callback) {
        if (!this.getProcessor.trySubmit(new GetObject(key, retryCount, callback),
                this.submitTimeout, this.submitTimeUnit)) {
            if (callback != null) {
                callback.timeout(key);
            }
        }
    }

    public interface GetCallback {
        public void handle(String key, Object value);

        public void timeout(String key);

        public void handleNull(String key);
    }
}
