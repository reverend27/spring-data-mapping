/* Copyright (C) 2010 SpringSource
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.datastore.mapping.redis.engine;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Collection;
import java.util.Date;
import java.util.List;

import org.springframework.dao.DataAccessException;
import org.springframework.datastore.mapping.core.SessionImplementor;
import org.springframework.datastore.mapping.engine.PropertyValueIndexer;
import org.springframework.datastore.mapping.model.MappingContext;
import org.springframework.datastore.mapping.model.PersistentEntity;
import org.springframework.datastore.mapping.model.PersistentProperty;
import org.springframework.datastore.mapping.redis.collection.RedisSet;
import org.springframework.datastore.mapping.redis.query.RedisQueryUtils;
import org.springframework.datastore.mapping.redis.util.RedisTemplate;

/**
 * Indexes property values for querying later
 *
 * @author Graeme Rocher
 * @since 1.0
 */
public class RedisPropertyValueIndexer implements PropertyValueIndexer<Long> {

    private RedisTemplate template;
    private PersistentProperty property;
    private RedisEntityPersister entityPersister;
    private MappingContext mappingContext;

    public RedisPropertyValueIndexer(MappingContext context, RedisEntityPersister redisEntityPersister, PersistentProperty property) {
        this.template = redisEntityPersister.getRedisTemplate();
        this.entityPersister = redisEntityPersister;
        this.property = property;
        this.mappingContext = context;
    }

    public void deindex(Object value, Long primaryKey) {
        if (value != null) {
            final String primaryIndex = createRedisKey(value);
            template.srem(primaryIndex, primaryKey);
        }
    }

    public void index(final Object value, final Long primaryKey) {
        if (value != null) {
            String propSortKey = entityPersister.getPropertySortKey(property);
            clearCachedIndices(entityPersister.getPropertySortKeyPattern());
            final String primaryIndex = createRedisKey(value);
            template.sadd(primaryIndex, primaryKey);
            // for numbers and dates we also create a list index in order to support range queries

            if (value instanceof Number) {
                Number n = (Number) value;
                template.zadd(propSortKey,n.doubleValue(),primaryKey);
            }
            else if (value instanceof Date) {
                Date d = (Date) value;
                Long time = d.getTime();
                template.zadd(propSortKey,time.doubleValue(),primaryKey);
            }
        }
    }

    private void clearCachedIndices(final String propSortKey) {
        final SessionImplementor session = (SessionImplementor) entityPersister.getSession();
        final String keyPattern = propSortKey + "~*";
        session.addPostFlushOperation(new KeyPatternRunnable(keyPattern) {
            public void run() {
                deleteKeys(template.keys(keyPattern));
            }
        });
    }

    private abstract class KeyPatternRunnable implements Runnable {
        private String keyPattern;

        public KeyPatternRunnable(String keyPattern) {
            this.keyPattern = keyPattern;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof KeyPatternRunnable) {
                return keyPattern.equals(((KeyPatternRunnable)obj).keyPattern);
            }
            return super.equals(obj);
        }

        @Override
        public int hashCode() {
            return keyPattern.hashCode();
        }
    }

    private void deleteKeys(List<String> toDelete) {
        if (toDelete != null && !toDelete.isEmpty())
            template.del(toDelete.toArray(new String[toDelete.size()]));
    }

    private String createRedisKey(Object value) {
        return getIndexRoot() + urlEncode(value);
    }

    private String urlEncode(Object value) {
        try {
            return URLEncoder.encode(value.toString(), "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new DataAccessException("Cannot encoding Redis key: " + e.getMessage(), e){};
        }
    }

    private String getIndexRoot() {
        return getRootEntityName() + ":" + property.getName() + ":";
    }

    private String getRootEntityName() {

        final PersistentEntity owner = property.getOwner();
        if (owner.isRoot()) {
            return owner.getName();
        }
        return owner.getRootEntity().getName();
    }

    public List<Long> query(final Object value) {
        return query(value, 0, -1);
    }

    public List<Long> query(final Object value, final int offset, final int max) {
        String redisKey = createRedisKey(value);

        RedisSet set = new RedisSet(template, redisKey);
        Collection<String> results;
        if (offset > 0 || max > 0) {
            results = set.members(offset, max);
        }
        else {
            results = set.members();
        }
        return RedisQueryUtils.transformRedisResults(mappingContext.getConversionService(), results);
    }

    public String getIndexName(Object value) {
        return createRedisKey(value);
    }

    public String getIndexPattern(String pattern) {
        String root = getIndexRoot();

        return root + urlEncode(pattern.replaceAll("%", "*"));
    }
}
