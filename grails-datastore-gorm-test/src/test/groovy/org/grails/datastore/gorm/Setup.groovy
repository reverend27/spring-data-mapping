package org.grails.datastore.gorm

import org.springframework.datastore.mapping.core.Session
import org.springframework.datastore.mapping.model.MappingContext
import org.springframework.datastore.mapping.model.PersistentEntity
import org.springframework.datastore.mapping.simple.SimpleMapDatastore
import org.springframework.datastore.mapping.transactions.DatastoreTransactionManager
import org.springframework.util.StringUtils
import org.springframework.validation.Errors
import org.springframework.validation.Validator

/**
 * @author graemerocher
 */
class Setup {

    static Session setup(classes) {
        def simple = new SimpleMapDatastore()
        for (cls in classes) {
            simple.mappingContext.addPersistentEntity(cls)
        }

        PersistentEntity entity = simple.mappingContext.persistentEntities.find {
            PersistentEntity e -> e.name.contains("TestEntity")}

        simple.mappingContext.addEntityValidator(entity, [
            supports: { Class c -> true },
            validate: { Object o, Errors errors ->
                if (!StringUtils.hasText(o.name)) {
                    errors.rejectValue("name", "name.is.blank")
                }
            }
        ] as Validator)

        def enhancer = new GormEnhancer(simple, new DatastoreTransactionManager(datastore: simple))
        enhancer.enhance()

        simple.mappingContext.addMappingContextListener({ e -> enhancer.enhance e } as MappingContext.Listener)

        simple.connect()
    }
}
