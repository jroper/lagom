/*
 * Copyright (C) 2016-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package com.lightbend.lagom.internal.javadsl.persistence.jdbc

import java.util.Optional

import javax.inject.{ Inject, Singleton }
import akka.actor.ActorSystem
import akka.persistence.jdbc.query.scaladsl.JdbcReadJournal
import com.lightbend.lagom.internal.javadsl.persistence.{ AbstractPersistentEntityRegistry, Context }
import com.lightbend.lagom.javadsl.persistence.PersistentEntity
import play.api.inject.Injector

/**
 * INTERNAL API
 */
@Singleton
private[lagom] final class JdbcPersistentEntityRegistry @Inject() (system: ActorSystem, injector: Injector, slickProvider: SlickProvider)
  extends AbstractPersistentEntityRegistry(system, injector) {

  private lazy val ensureTablesCreated = slickProvider.ensureTablesCreated()

  override def register[C, E, S](entityClass: Class[_ <: PersistentEntity[C, E, S]]): Unit = {
    ensureTablesCreated
    super.register(entityClass)
  }

  override private[lagom] def register[C, E, S](entityContextFactory: () => Context[PersistentEntity[C, E, S]]): Unit = {
    ensureTablesCreated
    super.register(entityContextFactory)
  }

  override protected val queryPluginId = Optional.of(JdbcReadJournal.Identifier)

}
