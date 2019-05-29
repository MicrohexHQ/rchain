package coop.rchain.casper.util.rholang

import coop.rchain.casper.protocol._
import coop.rchain.casper.util.EventConverter
import coop.rchain.models.PCost
import coop.rchain.rspace.trace

// TODO: Add post-state to individual InternalProcessedDeploy.
final case class InternalProcessedDeploy(
    deploy: DeployData,
    cost: PCost,
    deployLog: Seq[trace.Event],
    paymentLog: Seq[trace.Event],
    status: DeployStatus
) {

  def toProcessedDeploy: ProcessedDeploy =
    ProcessedDeploy(
      deploy = Some(deploy),
      cost = Some(cost),
      log = deployLog.map(EventConverter.toCasperEvent),
      paymentLog = paymentLog.map(EventConverter.toCasperEvent),
      errored = status.isFailed
    )

}

object InternalProcessedDeploy {

  def fromProcessedDeploy(pd: ProcessedDeploy): Option[InternalProcessedDeploy] =
    for {
      d <- pd.deploy
      c <- pd.cost
      l = pd.log.map(EventConverter.toRspaceEvent)
      p = pd.paymentLog.map(EventConverter.toRspaceEvent)
      s = if (pd.errored) UnknownFailure else Succeeded
    } yield InternalProcessedDeploy(d, c, l, p, s)
}
