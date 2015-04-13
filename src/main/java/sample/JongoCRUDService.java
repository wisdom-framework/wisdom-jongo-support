//TODO it seems to me you can only delete odjectids in jongo, even if jongo lets you manualy create ids of a differnt type

package sample;

import com.mongodb.DB;
import com.mongodb.WriteResult;
import org.bson.types.ObjectId;
import org.jongo.Jongo;
import org.jongo.MongoCollection;
import org.jongo.MongoCursor;
import org.wisdom.api.model.*;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;



public class JongoCRUDService<T> implements JongoCRUD<T> {

    private final DB db;
    private final Class<T> entityClass;
    private final MongoCollection collection;
    private final Jongo jongo;
    private final Field idField;

    public JongoCRUDService(Class<T> clazz, DB db) {
        this.db = db;
        this.entityClass = clazz;
        //Todo should call get data store?
        jongo = new Jongo(db);
        collection = jongo.getCollection(entityClass.getSimpleName());
        this.idField = findIdField();
    }

    public Field getIdField(){
        return idField;
    }

    public Object getIdFieldValue(T o){
        try {
            if(Modifier.isPrivate(idField.getModifiers())) {

                idField.setAccessible(true);
                //Todo search for getter method instead of using accessable or maybe we need to reset to not accessible?
                return idField.get(o);
            }

            return idField.get(o);
        } catch (IllegalAccessException e) {


            return null;
        }

    }
    /**
     * Jongo seems to be limited to ids that are named _id of type String annotated with @ObjectId or
     * any name of type string annotated with both @ObjectId and @Id. Otherwise there are problems when you later try
     * to remove the object.
     *
     * @return returns the field that has the correct annotations.
     */
    private Field findIdField() {
        //check all declared fields first
        for (Field field : entityClass.getDeclaredFields()) {
            if(checkAnnotations(field)!=null){
                return field;
            }
        }
        //If not found above check in the parent classes
        for (Field field : entityClass.getFields()) {
            if(checkAnnotations(field)!=null){
                return field;
            }
        }

        //not found at all we should throw an error or msg or something?
        return null;
    }

    /**
     * Check each field to see if it has the annotations we are looking for.
     * @param field from an entity.
     * @return the field if it has the correct annotations otherwise returns null. Assumes that there isn't more than
     * one field with correct annotations.
     */
    private Field checkAnnotations(Field field){
        boolean isAnnotationObjectId = false;
        boolean isAnnotationId = false;
        Class type = field.getType();
        String name = field.getName();



        Annotation[] annotationsList = field.getDeclaredAnnotations();
        //check annotation types
        for (Annotation annotation : annotationsList) {
            if (annotation.annotationType().equals(org.jongo.marshall.jackson.oid.ObjectId.class)) {
                isAnnotationObjectId = true;
            }
            if (annotation.annotationType().equals(org.jongo.marshall.jackson.oid.Id.class)) {
                isAnnotationId = true;
            }
        }
        //todo can propably be consolidated?
        if (name.contains("_id") && isAnnotationObjectId) {
            return field;
        }
        if (isAnnotationId && isAnnotationObjectId) {
            return field;
        }
        if (isAnnotationId){
            return field;
        }
        if(type.equals(org.jongo.marshall.jackson.oid.ObjectId.class)){
            return field;
        }

        return null;
    }

    /**
     * Gets the entity class that is using the database.
     *
     * @return the class.
     */
    @Override
    public Class<T> getEntityClass() {
        findIdField();
        return entityClass;
    }

    /**
     * Get the type of the Id of the entity.
     * The id should be auto generated by Jongo using the annotation @objectId with a field name of _id.
     *
     * @return type String.
     */
    @Override
    public Class<String> getIdClass() {
        return String.class;
    }

    @Override
    public Jongo getJongoDataStore() {
        return new Jongo(db);
    }

    /**
     * Save a new copy of the entity in the database if it doesn't not already exist. If the entity already exists
     * (i.e the same ID number) then it should update the existing copy.
     *
     * @param o the entity to save.
     * @return the updated entity with an id number.
     */
    @Override
    public T save(T o) {
        WriteResult result = collection.save(o);
        if (result.getError() != null) {
            throw new RuntimeException("Cannot save instance " + o + " in " + collection.getName() + " : " + result.getLastError());
        } else {
            return o;
        }
    }

    /**
     * Save a new copy of the entity in the iterable list if it doesn't exist, or updates if it does exists.
     * @param iterable the collection of entities to be saved.
     * @return an iterable of the collections of entities that were saved.
     */
    @Override
    public Iterable<T> save(Iterable<T> iterable) {
        List<T> list = new ArrayList<T>();
        for(T t : iterable){
            list.add(save(t));
        }
        iterable = list;
        return iterable;
    }

    /**
     * Find an object from the database by it's unique Id number.
     *
     * @param id the unique id of the object.
     * @return the object if it exsists, otherwise return null.
     */
    //TOdo currently only works if id is of type objectid
    @Override
    public T findOne(String id) {
        try {
            return collection.findOne(new ObjectId(id)).as(entityClass);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    //todo not tested
    @Override
    public T findOne(EntityFilter<T> tentityFilter) {
        for (T entity : findAll()) {
            if (tentityFilter.accept(entity)) {
                return entity;
            }
        }
        return null;

    }

    /**
     * Find all of the objects in a Mongo Collection.
     *
     * @return an iterable of the entity type.
     */
    @Override
    public Iterable<T> findAll() {
        return collection.find().as(entityClass);
    }

    @Override
    public Iterable<T> findAll(Iterable iterable) {
        //todo
        return null;
    }

    @Override
    public Iterable findAll(EntityFilter entityFilter) {
        //todo
        return null;
    }

    /**
     * TODO currently only works for type objectid
     * Delete an object by  from the collection if it exists.
     *
     * @param id of the object you wish to delete for.
     *           If the id doesn't exist there is an IllegalArgumentException.
     *           <p/>
     *           Note: as far as I can tell jongo only supports remove for object id types and not others.
     */
    @Override
    public void delete(String id) {
        deleteByOID(id);
    }

    /**todo not finished
     * Delete an object by  from the collection if it exists.
     * @param o is an object that is an entity. It needs to have a valid _id field.
     * @return returns the original object passed in.
     *         If the id doesn't exist there is an IllegalArgumentException.
     */
    @Override
    public T delete(T o) {
        deleteByQueryString(o);
       /* if(getIdFieldValue(o).getClass().equals(java.lang.String.class)){
            collection.remove(new ObjectId(String.valueOf(getIdFieldValue(o))));
        }*/

        return o;
    }


    /**
     * Delete a list of objects in the form of iterable from the collection if they exist.
     * @param iterable is an iterable of entities.
     * @return the original iterable.
     */
    @Override
    public Iterable<T> delete(Iterable<T> iterable) {
        for(T temp : iterable){

//TODO it stops running when it hits null but doesnt delete the rest of the items
            try {
                collection.remove(new ObjectId(String.valueOf(idField.get(temp))));
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            }
        }

        return iterable;
    }

    private void deleteByOID(String id){

        collection.remove(new ObjectId(id));
    }

    private void deleteByQueryString(T o){
        //TODO remove by the differnt types
        if(idField.getType().equals(java.lang.String.class));
        {
            //remove({_id: ObjectId("552b6ce7e4b0d2c08d915958")})
        }
        if(idField.getType().equals(java.lang.Long.class));

        long num = 12L;
        collection.remove("{_id: '"+num+"'}");

    }

    /**
     * Method provided by jongo to delete everything in the collection. Use with caution.
     */
    public void deleteAllFromCollection(){
        collection.remove();
    }

    /**todo only works for objectid
     * Checks to see if the object exsists in the Mongo Collection based on its ID.
     *
     * @param id of the object to search for.
     * @return true if found false if not found.
     */
    @Override
    public boolean exists(String id) {
        try {
            collection.findOne(new ObjectId(id)).as(entityClass);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }

    }


    /**
     * Count the number of objects that are of the entity type in a Mongo Collection.
     *
     * @return count as type Long.
     */
    @Override
    public long count() {
        MongoCursor<T> cursor = collection.find().as(entityClass);
        return cursor.count();
    }

    //todo
    @Override
    public Repository getRepository() {
        return null;
    }

    /*--------------------------------------------------------------------------------------------*/
    @Override
    public void executeTransactionalBlock(Runnable runnable) throws HasBeenRollBackException {
        throw new UnsupportedOperationException("MongoDB does not support transactions");

    }

    @Override
    public TransactionManager getTransactionManager() {
        throw new UnsupportedOperationException("MongoDB does not support transactions");
    }

    @Override
    //used to be R
    public FluentTransaction<T>.Intermediate transaction(Callable callable) {
        throw new UnsupportedOperationException("MongoDB does not support transactions");
    }

    @Override
    //used to be R
    public FluentTransaction<T> transaction() {
        throw new UnsupportedOperationException("MongoDB does not support transactions");
    }

    @Override
    //used to be A
    public T executeTransactionalBlock(Callable callable) throws HasBeenRollBackException {
        throw new UnsupportedOperationException("MongoDB does not support transactions");
    }
}
