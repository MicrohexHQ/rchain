package coop.rchain.rholang.interpreter

import cats.Parallel
import cats.effect.Sync
import cats.mtl.FunctorTell
import coop.rchain.crypto.hash.Blake2b512Random
import coop.rchain.metrics.Span
import coop.rchain.models.TaggedContinuation.TaggedCont.{Empty, ParBody, ScalaBodyRef}
import coop.rchain.models._
import coop.rchain.rholang.interpreter.Runtime.RhoTuplespace
import coop.rchain.rholang.interpreter.accounting._

trait Dispatch[M[_], A, K] {
  def dispatch(continuation: K, dataList: Seq[A], sequenceNumber: Int): M[Unit]
}

object Dispatch {

  // TODO: Make this function total
  def buildEnv(dataList: Seq[ListParWithRandom]): Env[Par] =
    Env.makeEnv(dataList.flatMap(_.pars): _*)
}

class RholangAndScalaDispatcher[M[_]] private (
    _dispatchTable: => Map[Long, (Seq[ListParWithRandom], Int) => M[Unit]]
)(implicit s: Sync[M], reducer: Reduce[M])
    extends Dispatch[M, ListParWithRandom, TaggedContinuation] {

  def dispatch(
      continuation: TaggedContinuation,
      dataList: Seq[ListParWithRandom],
      sequenceNumber: Int
  ): M[Unit] =
    continuation.taggedCont match {
      case ParBody(parWithRand) =>
        val env     = Dispatch.buildEnv(dataList)
        val randoms = parWithRand.randomState +: dataList.toVector.map(_.randomState)
        reducer.eval(parWithRand.body)(env, Blake2b512Random.merge(randoms), sequenceNumber)
      case ScalaBodyRef(ref) =>
        _dispatchTable.get(ref) match {
          case Some(f) =>
            f(
              dataList.map(dl => ListParWithRandom(dl.pars, dl.randomState)),
              sequenceNumber
            )
          case None => s.raiseError(new Exception(s"dispatch: no function for $ref"))
        }
      case Empty =>
        s.unit
    }
}

object RholangAndScalaDispatcher {

  def create[M[_], F[_]](
      tuplespace: RhoTuplespace[M],
      dispatchTable: => Map[Long, (Seq[ListParWithRandom], Int) => M[Unit]],
      urnMap: Map[String, Par]
  )(
      implicit
      cost: _cost[M],
      parallel: Parallel[M, F],
      s: Sync[M],
      ft: FunctorTell[M, Throwable]
  ): (Dispatch[M, ListParWithRandom, TaggedContinuation], Reduce[M]) = {

    implicit lazy val dispatcher: Dispatch[M, ListParWithRandom, TaggedContinuation] =
      new RholangAndScalaDispatcher(dispatchTable)

    implicit lazy val reducer: Reduce[M] =
      new DebruijnInterpreter[M, F](tuplespace, dispatcher, urnMap)

    (dispatcher, reducer)
  }
}
