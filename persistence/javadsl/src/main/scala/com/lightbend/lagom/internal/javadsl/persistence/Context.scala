/*
 * Copyright (C) 2016-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package com.lightbend.lagom.internal.javadsl.persistence

private[lagom] trait Context[C] {
  def createContextualObject(): C
  def apply[T](callback: => T): T
  def destroy(): Unit
}

private[lagom] object Context {
  def noContext[C](factory: () => C): () => Context[C] = {
    () =>
      new Context[C] {
        override def createContextualObject(): C = factory()
        override def apply[T](callback: => T): T = callback
        override def destroy(): Unit = ()
      }
  }
}