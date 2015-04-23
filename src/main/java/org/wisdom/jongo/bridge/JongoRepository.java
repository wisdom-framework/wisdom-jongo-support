package org.wisdom.jongo.bridge;

import com.mongodb.DB;
import org.apache.felix.ipojo.annotations.*;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.ServiceRegistration;
import org.osgi.util.tracker.BundleTracker;
import org.osgi.util.tracker.BundleTrackerCustomizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wisdom.api.model.Crud;
import org.wisdom.api.model.Repository;

import java.net.URL;
import java.util.*;

@Component
@Provides
public class JongoRepository implements Repository<DB>,
        BundleTrackerCustomizer<List<JongoRepository.InstantiatedCrud>> {

    private static final Logger LOGGER = LoggerFactory.getLogger(JongoRepository.class);

    @Requires(id = "database")
    private DB database;

    @Property(name = "entities", mandatory = true)
    private List<String> entities;

    @Context
    private BundleContext context;

    private BundleTracker<List<InstantiatedCrud>> tracker;

    @Validate
    public void start() {
        LOGGER.info("Starting Jongo Repository for {}", database.getName());
        LOGGER.info("Listed entities: {}", entities);

        tracker = new BundleTracker<>(context, Bundle.ACTIVE, this);
        tracker.open();
    }

    @Invalidate
    public void stop() {
        LOGGER.info("Stopping Jongo Repository for {}", database.getName());

        Map<Bundle, List<InstantiatedCrud>> map = new HashMap<>(tracker.getTracked());
        tracker.close();

        for (Map.Entry<Bundle, List<InstantiatedCrud>> entry : map.entrySet()) {
            for (InstantiatedCrud c : entry.getValue()) {
                c.unregister();
            }
        }
    }

    /**
     * Gets all Crud Service managed by the current repository. This allow retrieving the set of entity class managed
     * by the current repository.
     *
     * @return the set of Curd service, empty if none.
     */
    public Collection<Crud<?, ?>> getCrudServices() {
        List<Crud<?, ?>> cruds = new ArrayList<>();
        final Collection<List<InstantiatedCrud>> values = tracker.getTracked().values();
        for (List<InstantiatedCrud> list : values) {
            for (InstantiatedCrud c : list) {
                cruds.add(c.crud);
            }
        }
        return cruds;
    }

    /**
     * The name of the repository.
     *
     * @return the current repository name
     */
    public String getName() {
        return database.getName();
    }

    /**
     * The type of repository, generally the technology name.
     *
     * @return the type of repository
     */
    public String getType() {
        return "MongoDB";
    }

    /**
     * The class of the technical object represented by this repository. For instance, in the Ebean case,
     * it would be 'com.avaje.ebean.EbeanServer', while for MongoJack it would be 'org.mongojack.JacksonDBCollection'
     *
     * @return the class of the repository
     */
    public Class<DB> getRepositoryClass() {
        return DB.class;
    }

    /**
     * The technical object represented by this repository.
     *
     * @return the current repository
     */
    public DB get() {
        return database;
    }

    @Override
    public List<InstantiatedCrud> addingBundle(Bundle bundle, BundleEvent event) {
        // 1 for each listed entity check if the bundle contains it
        List<InstantiatedCrud> list = new ArrayList<>();
        for (String entity : entities) {
            URL url = bundle.getEntry(entity.replace(".", "/") + ".class");
            if (url != null) {
                LOGGER.info("Entity class {} found in {} ({})", entity, bundle.getSymbolicName(), bundle.getBundleId());
                // 2 for each contained class create an instantiated crud.
                Class clazz = load(bundle, entity);
                if (clazz != null) {
                    JongoCRUDService crud = new JongoCRUDService(clazz, database);
                    InstantiatedCrud ic = new InstantiatedCrud(bundle, crud, entity);
                    list.add(ic);
                }

            }
        }

        if (!list.isEmpty()) {
            // Register the crud
            for (InstantiatedCrud crud : list) {
                crud.register();
            }

            return list;
        }

        return null;
    }

    private Class load(Bundle bundle, String entity) {
        try {
            return bundle.loadClass(entity);
        } catch (ClassNotFoundException e) {
            LOGGER.error("Cannot load the class {} from {} ({})",
                    entity, bundle.getSymbolicName(), bundle.getBundleId(), e);
        }
        return null;
    }

    @Override
    public void modifiedBundle(Bundle bundle, BundleEvent event, List<InstantiatedCrud> object) {
        // Not supported
    }

    @Override
    public void removedBundle(Bundle bundle, BundleEvent event, List<InstantiatedCrud> object) {
        for (InstantiatedCrud c : object) {
            c.unregister();
        }
    }


    class InstantiatedCrud {
        private String entity;
        private Bundle bundle;
        private JongoCRUDService crud;

        private ServiceRegistration registration;

        public InstantiatedCrud(Bundle bundle, JongoCRUDService crud, String entity) {
            this.bundle = bundle;
            this.crud = crud;
            this.entity = entity;
        }

        public void unregister() {
            if (registration != null) {
                registration.unregister();
                registration = null;
            }
        }

        public void register() {
            crud.setRepository(JongoRepository.this);
            Dictionary<String, Object> properties = new Hashtable<>();
            properties.put(Crud.ENTITY_CLASS_PROPERTY, load(bundle, entity));
            properties.put(Crud.ENTITY_CLASSNAME_PROPERTY, entity);
            registration = context.registerService(Crud.class, crud, properties);
        }
    }
}
