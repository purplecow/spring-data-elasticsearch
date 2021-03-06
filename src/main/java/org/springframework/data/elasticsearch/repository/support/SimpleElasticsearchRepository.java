/*
 * Copyright 2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.elasticsearch.repository.support;

import org.elasticsearch.index.query.QueryBuilder;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.domain.*;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.query.*;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.util.Assert;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.elasticsearch.index.query.QueryBuilders.inQuery;
import static org.elasticsearch.index.query.QueryBuilders.matchAllQuery;
import static org.springframework.data.elasticsearch.core.query.Query.DEFAULT_PAGE;

/**
 * Elasticsearch specific repository implementation. Likely to be used as target within {@link ElasticsearchRepositoryFactory}
 *
 * @param <T>
 *
 * @author Rizwan Idrees
 * @author Mohsin Husen
 */
public class SimpleElasticsearchRepository<T> implements ElasticsearchRepository<T, String> {


    private ElasticsearchOperations elasticsearchOperations;
    private Class<T> entityClass;
    private ElasticsearchEntityInformation<T, String> entityInformation;

    public SimpleElasticsearchRepository() {
    }

    public SimpleElasticsearchRepository(ElasticsearchOperations elasticsearchOperations) {
        Assert.notNull(elasticsearchOperations);
        this.setElasticsearchOperations(elasticsearchOperations);
    }

    public SimpleElasticsearchRepository(ElasticsearchEntityInformation<T, String> metadata, ElasticsearchOperations elasticsearchOperations) {
        this(elasticsearchOperations);
        Assert.notNull(metadata);
        this.entityInformation = metadata;
        setEntityClass(this.entityInformation.getJavaType());
        createIndex();
        putMapping();
    }

    private void createIndex(){
        elasticsearchOperations.createIndex(getEntityClass());
    }

    private void putMapping(){
        elasticsearchOperations.putMapping(getEntityClass());
    }

    @Override
    public T findOne(String id) {
        GetQuery query = new GetQuery();
        query.setId(id);
        return elasticsearchOperations.queryForObject(query, getEntityClass());
    }

    @Override
    public Iterable<T> findAll() {
        int itemCount = (int) this.count();
        if (itemCount == 0) {
            return new PageImpl<T>(Collections.<T> emptyList());
        }
        return this.findAll(new PageRequest(0, Math.max(1, itemCount)));
    }

    @Override
    public Page<T> findAll(Pageable pageable) {
        SearchQuery query = new SearchQuery();
        query.setElasticsearchQuery(matchAllQuery());
        query.setPageable(pageable);
        return elasticsearchOperations.queryForPage(query, getEntityClass());
    }

    @Override
    public Iterable<T> findAll(Sort sort) {
        int itemCount = (int) this.count();
        if (itemCount == 0) {
            return new PageImpl<T>(Collections.<T> emptyList());
        }
        SearchQuery query = new SearchQuery();
        query.setElasticsearchQuery(matchAllQuery());
        query.setPageable(new PageRequest(0,itemCount, sort));
        return elasticsearchOperations.queryForPage(query, getEntityClass());
    }

    @Override
    public Iterable<T> findAll(Iterable<String> ids) {
        SearchQuery query = new SearchQuery();
        query.setElasticsearchQuery(inQuery(entityInformation.getIdAttribute(), ids));
        return elasticsearchOperations.queryForPage(query, getEntityClass());
    }

    @Override
    public long count() {
        SearchQuery query = new SearchQuery();
        return elasticsearchOperations.count(query,getEntityClass());
    }

    @Override
    public <S extends T> S save(S entity) {
        Assert.notNull(entity, "Cannot save 'null' entity.");
        elasticsearchOperations.index(createIndexQuery(entity));
        elasticsearchOperations.refresh(entityInformation.getIndexName(), true);
        return entity;
    }

    public <S extends T> List<S> save(List<S> entities) {
        Assert.notNull(entities, "Cannot insert 'null' as a List.");
        Assert.notEmpty(entities,"Cannot insert empty List.");
        List<IndexQuery> queries = new ArrayList<IndexQuery>();
        for(S  s:entities){
            queries.add(createIndexQuery(s));
        }
        elasticsearchOperations.bulkIndex(queries);
        elasticsearchOperations.refresh(entityInformation.getIndexName(), true);
        return entities;
    }

    @Override
    public <S extends T> S index(S entity) {
        return save(entity);
    }

    @Override
    public <S extends T> Iterable<S> save(Iterable<S> entities) {
        Assert.notNull(entities, "Cannot insert 'null' as a List.");
        if (!(entities instanceof Collection<?>)) {
            throw new InvalidDataAccessApiUsageException("Entities have to be inside a collection");
        }
        List<IndexQuery> queries = new ArrayList<IndexQuery>();
        for(S s: entities){
            queries.add(createIndexQuery(s));
        }
        elasticsearchOperations.bulkIndex(queries);
        elasticsearchOperations.refresh(entityInformation.getIndexName(), true);
        return entities;
    }

    @Override
    public boolean exists(String id) {
        return findOne(id) != null;
    }

    @Override
    public Iterable<T> search(QueryBuilder elasticsearchQuery) {
        SearchQuery query = new SearchQuery();
        int count = (int) elasticsearchOperations.count(query, getEntityClass());
        if(count == 0){
            return new PageImpl<T>(Collections.<T>emptyList());
        }
        query.setPageable(new PageRequest(0,count));
        query.setElasticsearchQuery(elasticsearchQuery);
        return elasticsearchOperations.queryForPage(query, getEntityClass());
    }

    @Override
    public Page<T> search(QueryBuilder elasticsearchQuery, Pageable pageable) {
        SearchQuery query = new SearchQuery();
        query.setElasticsearchQuery(elasticsearchQuery);
        query.setPageable(pageable);
        return elasticsearchOperations.queryForPage(query, getEntityClass());
    }

    @Override
    public Page<T> search(SearchQuery query){
        return elasticsearchOperations.queryForPage(query, getEntityClass());
    }

    @Override
    public Page<T> searchSimilar(T entity) {
        return searchSimilar(entity, DEFAULT_PAGE);
    }

    @Override
    public Page<T> searchSimilar(T entity, Pageable pageable) {
        Assert.notNull(entity, "Cannot search similar records for 'null'.");
        Assert.notNull(entity, "Pageable cannot be 'null'");
        MoreLikeThisQuery query = new MoreLikeThisQuery();
        query.setId(extractIdFromBean(entity));
        query.setPageable(pageable);
        return elasticsearchOperations.moreLikeThis(query, getEntityClass());
    }


    @Override
    public void delete(String id) {
        Assert.notNull(id, "Cannot delete entity with id 'null'.");
        elasticsearchOperations.delete(entityInformation.getIndexName(), entityInformation.getType(),id);
        elasticsearchOperations.refresh(entityInformation.getIndexName(),true);
    }

    @Override
    public void delete(T entity) {
        Assert.notNull(entity, "Cannot delete 'null' entity.");
        delete(extractIdFromBean(entity));
        elasticsearchOperations.refresh(entityInformation.getIndexName(), true);
    }

    @Override
    public void delete(Iterable<? extends T> entities) {
        Assert.notNull(entities, "Cannot delete 'null' list.");
        for (T entity : entities) {
            delete(entity);
        }
    }

    @Override
    public void deleteAll() {
        DeleteQuery query = new DeleteQuery();
        query.setElasticsearchQuery(matchAllQuery());
        elasticsearchOperations.delete(query, getEntityClass());
        elasticsearchOperations.refresh(entityInformation.getIndexName(),true);
    }

    private IndexQuery createIndexQuery(T entity){
        IndexQuery query = new IndexQuery();
        query.setObject(entity);
        query.setId(extractIdFromBean(entity));
        query.setVersion(extractVersionFromBean(entity));
        return query;
    }

    @SuppressWarnings("unchecked")
    private Class<T> resolveReturnedClassFromGenericType() {
        ParameterizedType parameterizedType = resolveReturnedClassFromGenericType(getClass());
        return (Class<T>) parameterizedType.getActualTypeArguments()[0];
    }

    private ParameterizedType resolveReturnedClassFromGenericType(Class<?> clazz) {
        Object genericSuperclass = clazz.getGenericSuperclass();
        if (genericSuperclass instanceof ParameterizedType) {
            ParameterizedType parameterizedType = (ParameterizedType) genericSuperclass;
            Type rawtype = parameterizedType.getRawType();
            if (SimpleElasticsearchRepository.class.equals(rawtype)) {
                return parameterizedType;
            }
        }
        return resolveReturnedClassFromGenericType(clazz.getSuperclass());
    }

    public Class<T> getEntityClass() {
        if (!isEntityClassSet()) {
            try {
                this.entityClass = resolveReturnedClassFromGenericType();
            } catch (Exception e) {
                throw new InvalidDataAccessApiUsageException("Unable to resolve EntityClass. Please use according setter!", e);
            }
        }
        return entityClass;
    }

    private boolean isEntityClassSet() {
        return entityClass != null;
    }

    public final void setEntityClass(Class<T> entityClass) {
        Assert.notNull(entityClass, "EntityClass must not be null.");
        this.entityClass = entityClass;
    }

    public final void setElasticsearchOperations(ElasticsearchOperations elasticsearchOperations) {
        Assert.notNull(elasticsearchOperations, "ElasticsearchOperations must not be null.");
        this.elasticsearchOperations = elasticsearchOperations;
    }


    private String extractIdFromBean(T entity) {
        if (entityInformation != null) {
            return entityInformation.getId(entity);
        }
        return null;
    }

    private Long extractVersionFromBean(T entity){
        if (entityInformation != null) {
            return entityInformation.getVersion(entity);
        }
        return null;
    }

}
