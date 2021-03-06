package com.github.gabrielgouv.microservice.core.repository.mongo;

import com.github.gabrielgouv.microservice.core.entity.BaseEntity;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.InsertOneResult;
import com.mongodb.client.result.UpdateResult;
import lombok.extern.slf4j.Slf4j;
import org.bson.BsonValue;

import javax.inject.Inject;
import java.lang.reflect.ParameterizedType;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.mongodb.client.model.Filters.*;

@Slf4j
@SuppressWarnings("unchecked")
public class MongoBaseRepository<T extends BaseEntity<String>> {

    protected static final String ID_FIELD = "_id";
    protected static final String DELETED_AT_FIELD = "deletedAt";

    private final MongoClient mongoClient;
    private final String databaseName;
    private final String collectionName;
    private final Class<T> typeOfT;

    @Inject
    public MongoBaseRepository(final MongoClient mongoClient, final String databaseName, final String collectionName) {
        this.mongoClient = mongoClient;
        this.databaseName = databaseName;
        this.collectionName = collectionName;
        this.typeOfT = (Class<T>) ((ParameterizedType) getClass()
                        .getGenericSuperclass())
                        .getActualTypeArguments()[0];
    }

    protected MongoCollection<T> getCollection() {
        return mongoClient.getDatabase(databaseName)
                .getCollection(collectionName, typeOfT);
    }

    protected T persist(T entity) {
        final String generatedId = generateId();
        entity.setId(generatedId);
        entity.setCreatedAt(LocalDateTime.now());
        final InsertOneResult result = getCollection().insertOne(entity);
        if (!result.wasAcknowledged()) {
            throw new RuntimeException("Cannot insert a new entity");
        }
        final BsonValue insertedId = result.getInsertedId();
        if (insertedId == null) {
            throw new RuntimeException("Cannot get inserted entity id");
        }
        log.info("Entity {} was inserted", getCollectionReference(insertedId.asString().getValue()));
        final T insertedEntity = getCollection().find(eq(ID_FIELD, generatedId)).first();
        if (insertedEntity == null) {
            throw new RuntimeException("Entity " + getCollectionReference(insertedId.asString().getValue())
                    + " was inserted but could not be returned");
        }
        return insertedEntity;
    }

    protected T update(T entity) {
        entity.setUpdatedAt(LocalDateTime.now());
        final UpdateResult result = getCollection().replaceOne(eq(ID_FIELD, entity.getId()), entity);
        final long modifiedCount = result.getModifiedCount();
        log.info("Entity {} was updated", getCollectionReference(entity.getId()));
        if (modifiedCount < 1) {
            throw new RuntimeException("Cannot find entity " + getCollectionReference(entity.getId()) + " to update");
        }
        if (modifiedCount > 1) {
            log.warn("Inconsistency: More than one entity with id {} was updated!", getCollectionReference(entity.getId()));
        }
        final T updatedEntity = getCollection().find(eq(ID_FIELD, entity.getId())).first();
        if (updatedEntity == null) {
            throw new RuntimeException("Entity " + getCollectionReference(entity.getId())
                    + " was updated but could not be returned");
        }
        return updatedEntity;
    }

    protected boolean delete(String id) {
        final DeleteResult result = getCollection().deleteOne(eq(ID_FIELD, id));
        final long deletedCount = result.getDeletedCount();
        if (deletedCount < 1) {
            throw new RuntimeException("Cannot find entity " + getCollectionReference(id) + " to delete");
        }
        if (deletedCount > 1) {
            log.warn("Inconsistency: More than one entity with id {} was deleted!", getCollectionReference(id));
        }
        log.info("Entity {} was deleted", getCollectionReference(id));
        return true;
    }

    protected boolean logicalDelete(String id) {
        final T foundEntity = getCollection().find(eq(ID_FIELD, id)).first();
        if (foundEntity == null) {
            throw new RuntimeException("Cannot find entity " + getCollectionReference(id) + " to logically delete");
        }
        final LocalDateTime now = LocalDateTime.now();
        foundEntity.setUpdatedAt(now);
        foundEntity.setDeletedAt(now);
        final UpdateResult result = getCollection().replaceOne(eq(ID_FIELD, id), foundEntity);
        final long modifiedCount = result.getModifiedCount();
        log.info("Entity {} was logically deleted", getCollectionReference(id));
        if (modifiedCount < 1) {
            throw new RuntimeException("Cannot logically delete " + getCollectionReference(id));
        }
        return true;
    }

    protected List<T> findAll() {
        final FindIterable<T> entitiesIterable = getCollection().find(exists(DELETED_AT_FIELD, false));
        final List<T> entities = new ArrayList<>();
        entitiesIterable.forEach(entities::add);
        return entities;
    }

    protected Optional<T> findOne(String id) {
        final T foundEntity = getCollection().find(eq(ID_FIELD, id)).first();
        if (foundEntity == null || foundEntity.getDeletedAt() != null) {
            return Optional.empty();
        }
        return Optional.of(foundEntity);
    }

    private String generateId() {
        return UUID.randomUUID().toString();
    }

    private String getCollectionReference(String id) {
        if (id != null) {
            return collectionName + "::" + id;
        }
        return collectionName + "::";
    }

}
